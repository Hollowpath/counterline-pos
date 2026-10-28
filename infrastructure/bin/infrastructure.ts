#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib/core';
import { CounterlineStack } from '../lib/infrastructure-stack';

const app = new cdk.App();
new CounterlineStack(app, 'Counterline');
