import * as cdk from 'aws-cdk-lib/core';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import { Construct } from 'constructs';

export class CounterlineStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const repository = new ecr.Repository(this, 'Repository', {
      repositoryName: 'counterline-pos',
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      imageScanOnPush: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      lifecycleRules: [
        {
          description: 'Keep only the 5 most recent images',
          maxImageCount: 5,
        },
      ],
    });

    new cdk.CfnOutput(this, 'RepositoryUri', {
      value: repository.repositoryUri,
    });

    const vpc = new ec2.Vpc(this, 'Vpc', {
      ipAddresses: ec2.IpAddresses.cidr('10.0.0.0/24'),
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        {
          name: 'Public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 26,
        },
        {
          name: 'Database',
          subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
          cidrMask: 26,
        },
      ],
    });

    new cdk.CfnOutput(this, 'VpcId', {
      value: vpc.vpcId,
    });

    const allowedIpv4Cidr = new cdk.CfnParameter(this, 'AllowedIpv4Cidr', {
      type: 'String',
      description: 'Owner public IPv4 address as one /32 CIDR',
      allowedPattern:
        '^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)/32$',
      constraintDescription: 'must be a valid IPv4 /32 CIDR',
    });

    const albSecurityGroup = new ec2.SecurityGroup(this, 'AlbSecurityGroup', {
      vpc,
      description: 'HTTP from the owner only',
      allowAllOutbound: false,
    });

    const serviceSecurityGroup = new ec2.SecurityGroup(
      this,
      'ServiceSecurityGroup',
      {
        vpc,
        description: 'Counterline application tasks',
        allowAllOutbound: true,
      },
    );

    const databaseSecurityGroup = new ec2.SecurityGroup(
      this,
      'DatabaseSecurityGroup',
      {
        vpc,
        description: 'Counterline PostgreSQL database',
        allowAllOutbound: false,
      },
    );

    albSecurityGroup.addIngressRule(
      ec2.Peer.ipv4(allowedIpv4Cidr.valueAsString),
      ec2.Port.tcp(80),
      'HTTP from owner IPv4 only',
    );

    albSecurityGroup.addEgressRule(
      serviceSecurityGroup,
      ec2.Port.tcp(8080),
      'HTTP to application tasks',
    );

    serviceSecurityGroup.addIngressRule(
      albSecurityGroup,
      ec2.Port.tcp(8080),
      'HTTP from the load balancer',
    );

    databaseSecurityGroup.addIngressRule(
      serviceSecurityGroup,
      ec2.Port.tcp(5432),
      'PostgreSQL from application tasks',
    );

    const database = new rds.DatabaseInstance(this, 'Database', {
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.of('17.11', '17'),
      }),
      instanceType: ec2.InstanceType.of(
        ec2.InstanceClass.T4G,
        ec2.InstanceSize.MICRO,
      ),
      vpc,
      vpcSubnets: {
        subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
      },
      securityGroups: [databaseSecurityGroup],
      databaseName: 'counterline',
      credentials: rds.Credentials.fromGeneratedSecret('counterline', {
        secretName: 'counterline/database',
      }),
      allocatedStorage: 20,
      storageType: rds.StorageType.GP3,
      multiAz: false,
      publiclyAccessible: false,
      storageEncrypted: true,
      backupRetention: cdk.Duration.days(1),
      deleteAutomatedBackups: true,
      deletionProtection: false,
      autoMinorVersionUpgrade: true,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const applicationSecret = new secretsmanager.Secret(
      this,
      'ApplicationSecret',
      {
        secretName: 'counterline/application',
        description: 'Counterline application keys and temporary bootstrap credentials',
        removalPolicy: cdk.RemovalPolicy.DESTROY,
      },
    );

    const logGroup = new logs.LogGroup(this, 'ApplicationLogGroup', {
      logGroupName: '/counterline/application',
      retention: logs.RetentionDays.ONE_WEEK,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const cluster = new ecs.Cluster(this, 'Cluster', {
      vpc,
      clusterName: 'counterline',
    });

    const taskDefinition = new ecs.FargateTaskDefinition(
      this,
      'ApplicationTaskDefinition',
      {
        cpu: 256,
        memoryLimitMiB: 512,
        runtimePlatform: {
          operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
          cpuArchitecture: ecs.CpuArchitecture.X86_64,
        },
      },
    );

    const container = taskDefinition.addContainer('ApplicationContainer', {
      containerName: 'counterline',
      image: ecs.ContainerImage.fromEcrRepository(repository, '9732963a0d52'),
      logging: ecs.LogDrivers.awsLogs({
        logGroup,
        streamPrefix: 'application',
      }),
      environment: {
        DB_URL: `jdbc:postgresql://${database.dbInstanceEndpointAddress}:${database.dbInstanceEndpointPort}/counterline`,
        POS_OUTLET_TIME_ZONE: 'Asia/Kuala_Lumpur',
        POS_OUTLET_PHONE_COUNTRY_CODE: '60',
        POS_BOOTSTRAP_ENABLED: 'false',
      },
      secrets: {
        DB_USERNAME: ecs.Secret.fromSecretsManager(database.secret!, 'username'),
        DB_PASSWORD: ecs.Secret.fromSecretsManager(database.secret!, 'password'),
        POS_SECURITY_PIN_PEPPER: ecs.Secret.fromSecretsManager(
          applicationSecret,
          'pinPepper',
        ),
        POS_SECURITY_JWT_SECRET: ecs.Secret.fromSecretsManager(
          applicationSecret,
          'jwtSecret',
        ),
      },
    });

    container.addPortMappings({
      containerPort: 8080,
      protocol: ecs.Protocol.TCP,
    });

    const service = new ecs.FargateService(this, 'ApplicationService', {
      cluster,
      taskDefinition,
      serviceName: 'counterline',
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: {
        subnetType: ec2.SubnetType.PUBLIC,
      },
      securityGroups: [serviceSecurityGroup],
      circuitBreaker: {
        rollback: true,
      },
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
      healthCheckGracePeriod: cdk.Duration.seconds(240),
    });

    const loadBalancer = new elbv2.ApplicationLoadBalancer(
      this,
      'ApplicationLoadBalancer',
      {
        vpc,
        internetFacing: true,
        securityGroup: albSecurityGroup,
        vpcSubnets: {
          subnetType: ec2.SubnetType.PUBLIC,
        },
        deletionProtection: false,
      },
    );

    const listener = loadBalancer.addListener('HttpListener', {
      port: 80,
      protocol: elbv2.ApplicationProtocol.HTTP,
      open: false,
    });

    listener.addTargets('ApplicationTargets', {
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [service],
      deregistrationDelay: cdk.Duration.seconds(30),
      healthCheck: {
        path: '/actuator/health',
        healthyHttpCodes: '200',
        interval: cdk.Duration.seconds(30),
        timeout: cdk.Duration.seconds(5),
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 3,
      },
    });

    new cdk.CfnOutput(this, 'ApplicationUrl', {
      value: `http://${loadBalancer.loadBalancerDnsName}`,
    });
  }
}
