"""Initialize only local Kinesis/DynamoDB resources; safe to rerun."""
import os
import boto3
from botocore.exceptions import ClientError

endpoint = "http://localhost:4566"
region = os.environ["AWS_REGION"]
kinesis = boto3.client("kinesis", endpoint_url=endpoint, region_name=region)
dynamo = boto3.client("dynamodb", endpoint_url=endpoint, region_name=region)
stream = os.environ["KINESIS_STREAM"]
try:
    kinesis.create_stream(StreamName=stream, ShardCount=1)
except ClientError as exc:
    if exc.response["Error"]["Code"] != "ResourceInUseException":
        raise
kinesis.get_waiter("stream_exists").wait(StreamName=stream)

for variable in ("ANALYTICS_TABLE", "PROCESSED_TABLE", "QUARANTINE_TABLE"):
    name = os.environ[variable]
    try:
        dynamo.create_table(
            TableName=name,
            KeySchema=[{"AttributeName": "PK", "KeyType": "HASH"}, {"AttributeName": "SK", "KeyType": "RANGE"}],
            AttributeDefinitions=[{"AttributeName": key, "AttributeType": "S"} for key in ("PK", "SK")],
            BillingMode="PAY_PER_REQUEST",
        )
    except ClientError as exc:
        if exc.response["Error"]["Code"] != "ResourceInUseException":
            raise
    dynamo.get_waiter("table_exists").wait(TableName=name)
    table = dynamo.describe_table(TableName=name)["Table"]
    if table["KeySchema"] != [{"AttributeName": "PK", "KeyType": "HASH"}, {"AttributeName": "SK", "KeyType": "RANGE"}]:
        raise RuntimeError("Existing table has incompatible keys")
    ttl = dynamo.describe_time_to_live(TableName=name)["TimeToLiveDescription"]
    if ttl["TimeToLiveStatus"] == "DISABLED":
        dynamo.update_time_to_live(TableName=name, TimeToLiveSpecification={"Enabled": True, "AttributeName": "expiresAt"})
    elif ttl.get("AttributeName") != "expiresAt":
        raise RuntimeError("Existing table has incompatible TTL configuration")
print("Local application resources initialized; KCL owns its coordination tables.")
