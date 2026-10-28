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
