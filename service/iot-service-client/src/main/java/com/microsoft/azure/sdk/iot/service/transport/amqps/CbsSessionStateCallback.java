package com.microsoft.azure.sdk.iot.service.transport.amqps;

/**
 * Interface that defines the callbacks used by the CBS session to notify the AMQP connection handler of authentication
 * status changes.
 */
interface CbsSessionStateCallback
{
    /**
     * Executes when the CBS session has successfully authenticated the connection
     */
    void onAuthenticationSucceeded();

    /**
     * Executes when the CBS session has failed to authenticate the connection
     * @param e the reason why authentication failed.
     */
    void onAuthenticationFailed(Exception e);
}
