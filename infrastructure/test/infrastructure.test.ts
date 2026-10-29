import * as cdk from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { CounterlineStack } from '../lib/infrastructure-stack';

test('creates the application image repository', () => {
  const app = new cdk.App();
  const stack = new CounterlineStack(app, 'Counterline');

  const template = Template.fromStack(stack);

  template.resourceCountIs('AWS::ECR::Repository', 1);
  template.hasResource('AWS::ECR::Repository', {
    Properties: {
      RepositoryName: 'counterline-pos',
      ImageTagMutability: 'IMMUTABLE',
      ImageScanningConfiguration: { ScanOnPush: true },
      LifecyclePolicy: {
        LifecyclePolicyText: Match.serializedJson({
          rules: [
            Match.objectLike({
              selection: Match.objectLike({
                tagStatus: 'any',
                countType: 'imageCountMoreThan',
                countNumber: 5,
              }),
              action: { type: 'expire' },
            }),
          ],
        }),
      },
    },
    DeletionPolicy: 'Retain',
    UpdateReplacePolicy: 'Retain',
  });
  template.hasOutput('RepositoryUri', {});
});

test('creates a two-AZ VPC without paid network egress', () => {
  const app = new cdk.App();
  const stack = new CounterlineStack(app, 'Counterline');
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::EC2::VPC', {
    CidrBlock: '10.0.0.0/24',
  });
  template.resourceCountIs('AWS::EC2::Subnet', 4);
  template.resourceCountIs('AWS::EC2::NatGateway', 0);
  template.resourceCountIs('AWS::EC2::VPCEndpoint', 0);

  const subnets = Object.values(template.findResources('AWS::EC2::Subnet'));
  expect(
    subnets.filter((subnet) => subnet.Properties.MapPublicIpOnLaunch),
  ).toHaveLength(2);
  expect(
    subnets.filter((subnet) => !subnet.Properties.MapPublicIpOnLaunch),
  ).toHaveLength(2);
});

test('restricts network access to the owner and required service paths', () => {
  const app = new cdk.App();
  const stack = new CounterlineStack(app, 'Counterline');
  const template = Template.fromStack(stack);

  template.hasParameter('AllowedIpv4Cidr', {
    Type: 'String',
  });
  template.resourceCountIs('AWS::EC2::SecurityGroup', 3);

  const resources = template.toJSON().Resources;
  const ingress = Object.values(resources).flatMap((resource: any) => {
    if (resource.Type === 'AWS::EC2::SecurityGroupIngress') {
      return [resource.Properties];
    }
    if (resource.Type === 'AWS::EC2::SecurityGroup') {
      return resource.Properties.SecurityGroupIngress ?? [];
    }
    return [];
  });

  expect(ingress).toHaveLength(3);
  expect(ingress.map((rule: any) => rule.FromPort)).toEqual(
    expect.arrayContaining([80, 8080, 5432]),
  );
  expect(
    ingress.some((rule: any) => rule.CidrIp === '0.0.0.0/0'),
  ).toBe(false);
  expect(
    ingress.some(
      (rule: any) =>
        rule.FromPort === 80 &&
        rule.CidrIp?.Ref === 'AllowedIpv4Cidr',
    ),
  ).toBe(true);
});

test('creates an encrypted isolated PostgreSQL database', () => {
  const app = new cdk.App();
  const stack = new CounterlineStack(app, 'Counterline');
  const template = Template.fromStack(stack);

  template.hasResource('AWS::RDS::DBInstance', {
    DeletionPolicy: 'Delete',
    UpdateReplacePolicy: 'Delete',
    Properties: Match.objectLike({
      Engine: 'postgres',
      EngineVersion: '17.11',
      DBInstanceClass: 'db.t4g.micro',
      DBName: 'counterline',
      AllocatedStorage: '20',
      StorageType: 'gp3',
      MultiAZ: false,
      PubliclyAccessible: false,
      StorageEncrypted: true,
      BackupRetentionPeriod: 1,
      DeleteAutomatedBackups: true,
      DeletionProtection: false,
    }),
  });

  template.resourceCountIs('AWS::RDS::DBSubnetGroup', 1);

  template.hasResourceProperties('AWS::SecretsManager::Secret', {
    Name: 'counterline/database',
    GenerateSecretString: Match.objectLike({
      GenerateStringKey: 'password',
      PasswordLength: 30,
      SecretStringTemplate: '{"username":"counterline"}',
    }),
  });
});

test('creates application secrets and short-retention logs safely', () => {
  const app = new cdk.App();
  const stack = new CounterlineStack(app, 'Counterline');
  const template = Template.fromStack(stack);

  template.hasResource('AWS::SecretsManager::Secret', {
    DeletionPolicy: 'Delete',
    UpdateReplacePolicy: 'Delete',
    Properties: Match.objectLike({
      Name: 'counterline/application',
      Description:
        'Counterline application keys and temporary bootstrap credentials',
      GenerateSecretString: {},
    }),
  });

  template.hasResource('AWS::Logs::LogGroup', {
    DeletionPolicy: 'Delete',
    UpdateReplacePolicy: 'Delete',
    Properties: {
      LogGroupName: '/counterline/application',
      RetentionInDays: 7,
    },
  });
});

test('creates a single-task owner-only Fargate application behind an ALB', () => {
  const app = new cdk.App();
  const stack = new CounterlineStack(app, 'Counterline');
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::ECS::TaskDefinition', {
    Cpu: '256',
    Memory: '512',
    NetworkMode: 'awsvpc',
    RequiresCompatibilities: ['FARGATE'],
    RuntimePlatform: {
      CpuArchitecture: 'X86_64',
      OperatingSystemFamily: 'LINUX',
    },
    ContainerDefinitions: Match.arrayWith([
      Match.objectLike({
        Name: 'counterline',
        PortMappings: [
          {
            ContainerPort: 8080,
            Protocol: 'tcp',
          },
        ],
      }),
    ]),
  });

  template.hasResourceProperties('AWS::ECS::Service', {
    DesiredCount: 1,
    LaunchType: 'FARGATE',
    HealthCheckGracePeriodSeconds: 240,
    DeploymentConfiguration: Match.objectLike({
      MaximumPercent: 200,
      MinimumHealthyPercent: 100,
    }),
    NetworkConfiguration: {
      AwsvpcConfiguration: Match.objectLike({
        AssignPublicIp: 'ENABLED',
      }),
    },
    LoadBalancers: Match.arrayWith([
      Match.objectLike({
        ContainerName: 'counterline',
        ContainerPort: 8080,
      }),
    ]),
  });

  template.hasResourceProperties(
    'AWS::ElasticLoadBalancingV2::LoadBalancer',
    {
      Scheme: 'internet-facing',
      Type: 'application',
    },
  );

  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
    Port: 80,
    Protocol: 'HTTP',
  });

  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
    Port: 8080,
    Protocol: 'HTTP',
    TargetType: 'ip',
    HealthCheckPath: '/actuator/health',
    Matcher: {
      HttpCode: '200',
    },
  });
});