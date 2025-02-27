package software.amazon.sns;

import com.amazon.sqs.javamessaging.SQSExtendedClientConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import software.amazon.payloadoffloading.PayloadStore;
import software.amazon.payloadoffloading.S3BackedPayloadStore;
import software.amazon.payloadoffloading.S3Dao;
import software.amazon.payloadoffloading.Util;

import java.util.HashMap;
import java.util.Map;

public class AmazonSNSExtendedClient extends AmazonSNSExtendedClientBase {
    static final String MULTIPLE_PROTOCOL_MESSAGE_STRUCTURE = "json";
    static final String USER_AGENT_HEADER_NAME = "User-Agent";
    static final String USER_AGENT_HEADER = Util.getUserAgentHeader(AmazonSNSExtendedClient.class.getSimpleName());

    private static final Log LOGGER = LogFactory.getLog(AmazonSNSExtendedClient.class);
    private static final String S3_KEY = "S3Key";
    private PayloadStore payloadStore;
    private SNSExtendedClientConfiguration snsExtendedClientConfiguration;

    /**
     * Constructs a new Amazon SNS extended client to invoke service methods on
     * Amazon SNS with extended functionality using the specified Amazon SNS
     * client object.
     * <p>
     * <p>
     * All service calls made using this new client object are blocking, and
     * will not return until the service call completes.
     *
     * @param snsClient                   The Amazon SNS client to use to connect to Amazon SNS.
     * @param snsExtendedClientConfiguration The sns extended client configuration options controlling the
     *                                    functionality of this client.
     */
    public AmazonSNSExtendedClient(SnsClient snsClient, SNSExtendedClientConfiguration snsExtendedClientConfiguration) {
        super(snsClient);

        this.snsExtendedClientConfiguration = snsExtendedClientConfiguration;
        S3Dao s3Dao = new S3Dao(this.snsExtendedClientConfiguration.getS3Client());
        this.payloadStore = new S3BackedPayloadStore(s3Dao, this.snsExtendedClientConfiguration.getS3BucketName());
    }

    /**
     * <p>
     * Sends a message to an Amazon SNS topic or sends a text message (SMS message) directly to a phone number.
     * </p>
     * <p>
     * If you send a message to a topic, Amazon SNS delivers the message to each endpoint that is subscribed to the
     * topic. The format of the message depends on the notification protocol for each subscribed endpoint.
     * </p>
     * <p>
     * When a <code>messageId</code> is returned, the message has been saved and Amazon SNS will attempt to deliver it
     * shortly.
     * </p>
     * <p>
     * To use the <code>Publish</code> action for sending a message to a mobile endpoint, such as an app on a Kindle
     * device or mobile phone, you must specify the EndpointArn for the TargetArn parameter. The EndpointArn is returned
     * when making a call with the <code>CreatePlatformEndpoint</code> action.
     * </p>
     * <p>
     * For more information about formatting messages, see <a
     * href="http://docs.aws.amazon.com/sns/latest/dg/mobile-push-send-custommessage.html">Send Custom Platform-Specific
     * Payloads in Messages to Mobile Devices</a>.
     * </p>
     *
     * @param publishRequest Input for Publish action.
     * @return Result of the Publish operation returned by the service.
     * @throws InvalidParameterException            Indicates that a request parameter does not comply with the associated constraints.
     * @throws InvalidParameterValueException       Indicates that a request parameter does not comply with the associated constraints.
     * @throws InternalErrorException               Indicates an internal service error.
     * @throws NotFoundException                    Indicates that the requested resource does not exist.
     * @throws EndpointDisabledException            Exception error indicating endpoint disabled.
     * @throws PlatformApplicationDisabledException Exception error indicating platform application disabled.
     * @throws AuthorizationErrorException          Indicates that the user has been denied access to the requested resource.
     * @sample AmazonSNS.Publish
     * @see <a href="http://docs.aws.amazon.com/goto/WebAPI/sns-2010-03-31/Publish" target="_top">AWS API
     * Documentation</a>
     */
    @Override
    public PublishResponse publish(PublishRequest publishRequest) {
        if (publishRequest == null || isNullOrEmpty(publishRequest.message())) {
            return super.publish(publishRequest);
        }

        if (!isNullOrEmpty(publishRequest.messageStructure()) &&
                publishRequest.messageStructure().equals(MULTIPLE_PROTOCOL_MESSAGE_STRUCTURE)) {
            String errorMessage = "SNS extended client does not support sending JSON messages.";
            LOGGER.error(errorMessage);
            throw SdkClientException.create(errorMessage);
        }

        PublishRequest.Builder publishRequestBuilder = publishRequest.toBuilder();
        publishRequestBuilder.overrideConfiguration(AwsRequestOverrideConfiguration.builder().putHeader(USER_AGENT_HEADER_NAME, USER_AGENT_HEADER).build());
        publishRequest = publishRequestBuilder.build();

        long messageAttributesSize = getMsgAttributesSize(publishRequest.messageAttributes());
        long messageBodySize = Util.getStringSizeInBytes(publishRequest.message());

        if (!shouldExtendedStoreBeUsed(messageAttributesSize + messageBodySize)) {
            return super.publish(publishRequest);
        }

        checkMessageAttributes(publishRequest.messageAttributes());
        checkSizeOfMessageAttributes(messageAttributesSize);

        PublishRequest clonedPublishRequest = copyPublishRequest(publishRequest);
        publishRequest = storeMessageInExtendedStore(clonedPublishRequest, messageAttributesSize);

        return super.publish(publishRequest);
    }

    private boolean isNullOrEmpty(String message) {
        return message == null || message.isEmpty();
    }

    private boolean shouldExtendedStoreBeUsed(long totalMessageSize) {
        return snsExtendedClientConfiguration.isAlwaysThroughS3() ||
                (snsExtendedClientConfiguration.isPayloadSupportEnabled() && isTotalMessageSizeLargerThanThreshold(totalMessageSize));
    }

    private void checkMessageAttributes(Map<String, MessageAttributeValue> messageAttributes) {
        int messageAttributesNum = messageAttributes.size();
        if (messageAttributesNum > SQSExtendedClientConstants.MAX_ALLOWED_ATTRIBUTES) {
            String errorMessage = "Number of message attributes [" + messageAttributesNum
                    + "] exceeds the maximum allowed for large-payload messages ["
                    + SQSExtendedClientConstants.MAX_ALLOWED_ATTRIBUTES + "].";
            LOGGER.error(errorMessage);
            throw SdkClientException.create(errorMessage);
        }

        MessageAttributeValue largePayloadAttributeName = messageAttributes.get(SQSExtendedClientConstants.RESERVED_ATTRIBUTE_NAME);

        if (largePayloadAttributeName != null) {
            String errorMessage = "Message attribute name " + SQSExtendedClientConstants.RESERVED_ATTRIBUTE_NAME
                    + " is reserved for use by SNS extended client.";
            LOGGER.error(errorMessage);
            throw SdkClientException.create(errorMessage);
        }
    }

    private void checkSizeOfMessageAttributes(long messageAttributeSize) {
        if (messageAttributeSize > snsExtendedClientConfiguration.getPayloadSizeThreshold()) {
            String errorMessage = "Total size of Message attributes is " + messageAttributeSize
                    + " bytes which is larger than the threshold of " + snsExtendedClientConfiguration.getPayloadSizeThreshold()
                    + " Bytes. Consider including the payload in the message body instead of message attributes.";
            LOGGER.error(errorMessage);
            throw SdkClientException.create(errorMessage);
        }
    }

    private boolean isTotalMessageSizeLargerThanThreshold(long totalMessageSize) {
        return (totalMessageSize > snsExtendedClientConfiguration.getPayloadSizeThreshold());
    }

    private int getMsgAttributesSize(Map<String, MessageAttributeValue> msgAttributes) {
        int totalMsgAttributesSize = 0;

        for (Map.Entry<String, MessageAttributeValue> entry : msgAttributes.entrySet()) {
            totalMsgAttributesSize += getMessageAttributeSize(entry.getKey(), entry.getValue());
        }

        return totalMsgAttributesSize;
    }

    private long getMessageAttributeSize(String MessageAttributeKey, MessageAttributeValue value) {
        long messageAttributeSize = Util.getStringSizeInBytes(MessageAttributeKey);

        if (value.dataType() != null) {
            messageAttributeSize += Util.getStringSizeInBytes(value.dataType());
        }

        String stringVal = value.stringValue();
        if (stringVal != null) {
            messageAttributeSize += Util.getStringSizeInBytes(stringVal);
        }

        SdkBytes binaryVal = value.binaryValue();
        if (binaryVal != null) {
            messageAttributeSize += binaryVal.asByteArray().length;
        }

        return messageAttributeSize;
    }

    private static String getS3keyAttribute(Map<String, MessageAttributeValue> messageAttributes) {
        if (messageAttributes != null && messageAttributes.containsKey(S3_KEY)) {
            MessageAttributeValue attributeS3KeyValue = messageAttributes.get(S3_KEY);
            return (attributeS3KeyValue == null) ? null : attributeS3KeyValue.stringValue();
        }
        return null;
    }

    private PublishRequest storeMessageInExtendedStore(PublishRequest publishRequest, long messageAttributeSize) {
        String messageContentStr = publishRequest.message();
        Long messageContentSize = Util.getStringSizeInBytes(messageContentStr);

        PublishRequest.Builder publishRequestBuilder = publishRequest.toBuilder();
        String largeMessagePointer = payloadStore.storeOriginalPayload(messageContentStr);
        publishRequestBuilder.message(largeMessagePointer);

        MessageAttributeValue.Builder messageAttributeValueBuilder = MessageAttributeValue.builder();
        messageAttributeValueBuilder.dataType("Number");
        messageAttributeValueBuilder.stringValue(messageContentSize.toString());
        MessageAttributeValue messageAttributeValue = messageAttributeValueBuilder.build();

        Map<String, MessageAttributeValue> attributes = new HashMap<>(publishRequest.messageAttributes());
        attributes.put(SQSExtendedClientConstants.RESERVED_ATTRIBUTE_NAME, messageAttributeValue);
        publishRequestBuilder.messageAttributes(attributes);
        publishRequestBuilder.message(largeMessagePointer);

        messageAttributeSize += getMessageAttributeSize(SQSExtendedClientConstants.RESERVED_ATTRIBUTE_NAME, messageAttributeValue);
        checkSizeOfMessageAttributes(messageAttributeSize);

        return publishRequestBuilder.build();
    }

    private PublishRequest copyPublishRequest(PublishRequest publishRequest) {
        // We only modify Message and MessageAttributes, to avoid performance impact let's shallow-copy 
        // the request and then copy the MessageAttributes map.
        PublishRequest.Builder publishRequestBuilder = publishRequest.toBuilder();
        Map<String, MessageAttributeValue> attributes = new HashMap<>(publishRequest.messageAttributes());
        publishRequestBuilder.messageAttributes(attributes);
        return publishRequestBuilder.build();
    }
}
