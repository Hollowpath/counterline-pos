import * as cdk from 'aws-cdk-lib/core';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
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
  }
}
