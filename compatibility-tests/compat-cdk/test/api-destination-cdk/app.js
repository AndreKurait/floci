const cdk = require('aws-cdk-lib');
const events = require('aws-cdk-lib/aws-events');
const iam = require('aws-cdk-lib/aws-iam');
const secretsmanager = require('aws-cdk-lib/aws-secretsmanager');

const prefix = process.env.API_DESTINATION_PREFIX;
const sinkUrl = process.env.API_DESTINATION_SINK_URL;
const phase = process.env.API_DESTINATION_PHASE || 'initial';
const nameSuffix = phase === 'renamed' ? '-renamed' : '';
if (!prefix || !sinkUrl) {
    throw new Error('API_DESTINATION_PREFIX and API_DESTINATION_SINK_URL are required');
}

const app = new cdk.App();
const stack = new cdk.Stack(app, prefix, {
    env: { account: '000000000000', region: 'us-east-1' },
    synthesizer: new cdk.BootstraplessSynthesizer(),
});
const secretName = `${prefix}-secret`;
const secret = new secretsmanager.CfnSecret(stack, 'Secret', {
    name: secretName,
    secretString: JSON.stringify({ key: `synthetic-${phase}` }),
});
const connection = new events.CfnConnection(stack, 'Connection', {
    name: `${prefix}-auth${nameSuffix}`,
    description: phase,
    authorizationType: 'API_KEY',
    authParameters: {
        apiKeyAuthParameters: {
            apiKeyName: 'X-Key',
            apiKeyValue: `{{resolve:secretsmanager:${secretName}:SecretString:key}}`,
        },
    },
});
connection.addDependency(secret);
const destination = new events.CfnApiDestination(stack, 'Destination', {
    name: `${prefix}-destination${nameSuffix}`,
    description: phase,
    connectionArn: connection.attrArn,
    invocationEndpoint: `${sinkUrl}/${phase}/*`,
    httpMethod: 'POST',
    invocationRateLimitPerSecond: phase === 'initial' ? 42 : 84,
});
const role = new iam.CfnRole(stack, 'InvokeRole', {
    assumeRolePolicyDocument: {
        Version: '2012-10-17',
        Statement: [{ Effect: 'Allow', Principal: { Service: 'events.amazonaws.com' }, Action: 'sts:AssumeRole' }],
    },
    policies: [{
        policyName: 'InvokeApiDestination',
        policyDocument: {
            Version: '2012-10-17',
            Statement: [{ Effect: 'Allow', Action: 'events:InvokeApiDestination', Resource: destination.attrArnForPolicy }],
        },
    }],
});
new events.CfnRule(stack, 'Rule', {
    name: prefix,
    eventPattern: { source: [prefix] },
    targets: [{
        id: 'http',
        arn: destination.attrArn,
        roleArn: role.attrArn,
        input: JSON.stringify({ value: 'ok' }),
        httpParameters: {
            pathParameterValues: ['item'],
            headerParameters: { 'X-Target': phase },
            queryStringParameters: { version: '2' },
        },
        retryPolicy: { maximumRetryAttempts: 0, maximumEventAgeInSeconds: 60 },
    }],
});
new cdk.CfnOutput(stack, 'ConnectionName', { value: connection.ref });
new cdk.CfnOutput(stack, 'ConnectionArn', { value: connection.attrArn });
new cdk.CfnOutput(stack, 'ConnectionPolicyArn', { value: connection.attrArnForPolicy });
new cdk.CfnOutput(stack, 'SecretArn', { value: connection.attrSecretArn });
new cdk.CfnOutput(stack, 'DestinationName', { value: destination.ref });
new cdk.CfnOutput(stack, 'DestinationArn', { value: destination.attrArn });
new cdk.CfnOutput(stack, 'DestinationPolicyArn', { value: destination.attrArnForPolicy });
