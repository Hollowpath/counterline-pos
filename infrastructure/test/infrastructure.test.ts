import * as cdk from 'aws-cdk-lib/core';
import { Template } from 'aws-cdk-lib/assertions';
import { CounterlineStack } from '../lib/infrastructure-stack';

test('the stack synthesizes with no resources', () => {
  const app = new cdk.App();
  const stack = new CounterlineStack(app, 'Counterline');

  const template = Template.fromStack(stack);

  expect(template.toJSON().Resources ?? {}).toEqual({});
});
