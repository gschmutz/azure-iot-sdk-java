/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.service.transport.amqps;

import com.azure.core.credential.TokenCredential;
import com.microsoft.azure.proton.transport.proxy.ProxyHandler;
import com.microsoft.azure.proton.transport.proxy.impl.ProxyHandlerImpl;
import com.microsoft.azure.proton.transport.proxy.impl.ProxyImpl;
import com.microsoft.azure.sdk.iot.deps.auth.IotHubSSLContext;
import com.microsoft.azure.sdk.iot.deps.transport.amqp.AuthenticationType;
import com.microsoft.azure.sdk.iot.deps.transport.amqp.ErrorLoggingBaseHandlerWithCleanup;
import com.microsoft.azure.sdk.iot.deps.ws.impl.WebSocketImpl;
import com.microsoft.azure.sdk.iot.service.IotHubServiceClientProtocol;
import com.microsoft.azure.sdk.iot.service.ProxyOptions;
import com.microsoft.azure.sdk.iot.service.Tools;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Event;
import org.apache.qpid.proton.engine.Session;
import org.apache.qpid.proton.engine.SslDomain;
import org.apache.qpid.proton.engine.Transport;
import org.apache.qpid.proton.engine.impl.TransportInternal;
import org.apache.qpid.proton.reactor.Reactor;

import javax.net.ssl.SSLContext;
import java.io.IOException;

@Slf4j
public abstract class AmqpConnectionHandler extends ErrorLoggingBaseHandlerWithCleanup implements CbsSessionStateCallback
{
    public static final String WEBSOCKET_PATH = "/$iothub/websocket";
    public static final String WEBSOCKET_SUB_PROTOCOL = "AMQPWSB10";
    public static final int AMQPS_PORT = 5671;
    public static final int AMQPS_WS_PORT = 443;

    protected Exception savedException;
    protected boolean connectionOpenedRemotely;
    protected boolean sessionOpenedRemotely;
    protected boolean linkOpenedRemotely;

    protected final String hostName;
    protected String userName;
    protected String sasToken;
    protected TokenCredential authenticationTokenProvider;
    protected AuthenticationType authorizationType;
    protected final IotHubServiceClientProtocol iotHubServiceClientProtocol;
    protected final ProxyOptions proxyOptions;
    protected final SSLContext sslContext;

    protected Connection connection;
    private CbsSessionHandler cbsSessionHandler;

    protected AmqpConnectionHandler(String hostName, String userName, String sasToken, IotHubServiceClientProtocol iotHubServiceClientProtocol, ProxyOptions proxyOptions, SSLContext sslContext)
    {
        if (Tools.isNullOrEmpty(hostName))
        {
            throw new IllegalArgumentException("hostName can not be null or empty");
        }

        if (Tools.isNullOrEmpty(userName))
        {
            throw new IllegalArgumentException("userName can not be null or empty");
        }

        if (Tools.isNullOrEmpty(sasToken))
        {
            throw new IllegalArgumentException("sasToken can not be null or empty");
        }

        if (iotHubServiceClientProtocol == null)
        {
            throw new IllegalArgumentException("iotHubServiceClientProtocol cannot be null");
        }

        this.savedException = null;
        this.connectionOpenedRemotely = false;
        this.sessionOpenedRemotely = false;
        this.linkOpenedRemotely = false;

        this.iotHubServiceClientProtocol = iotHubServiceClientProtocol;
        this.proxyOptions = proxyOptions;
        this.hostName = hostName;
        this.userName = userName;
        this.sasToken = sasToken;
        this.sslContext = sslContext; // if null, a default SSLContext will be generated for the user
    }

    protected AmqpConnectionHandler(String hostName, TokenCredential authenticationTokenProvider, AuthenticationType authorizationType, IotHubServiceClientProtocol iotHubServiceClientProtocol, ProxyOptions proxyOptions, SSLContext sslContext)
    {
        if (Tools.isNullOrEmpty(hostName))
        {
            throw new IllegalArgumentException("hostName can not be null or empty");
        }

        if (iotHubServiceClientProtocol == null)
        {
            throw new IllegalArgumentException("iotHubServiceClientProtocol cannot be null");
        }

        this.savedException = null;
        this.connectionOpenedRemotely = false;
        this.sessionOpenedRemotely = false;
        this.linkOpenedRemotely = false;

        this.iotHubServiceClientProtocol = iotHubServiceClientProtocol;
        this.proxyOptions = proxyOptions;
        this.hostName = hostName;
        this.sslContext = sslContext; // if null, a default SSLContext will be generated for the user
        this.authenticationTokenProvider = authenticationTokenProvider;
        this.authorizationType = authorizationType;
    }

    @Override
    public void onReactorInit(Event event)
    {
        Reactor reactor = event.getReactor();

        if (this.iotHubServiceClientProtocol == IotHubServiceClientProtocol.AMQPS_WS)
        {
            if (proxyOptions != null)
            {
                reactor.connectionToHost(proxyOptions.getHostName(), proxyOptions.getPort(), this);
            }
            else
            {
                reactor.connectionToHost(this.hostName, AMQPS_WS_PORT, this);
            }
        }
        else
        {
            reactor.connectionToHost(this.hostName, AMQPS_PORT, this);
        }
    }

    /**
     * Event handler for the connection bound event
     * @param event The proton event object
     */
    @Override
    public void onConnectionBound(Event event)
    {
        Transport transport = event.getConnection().getTransport();
        if (transport != null)
        {
            if (this.iotHubServiceClientProtocol == IotHubServiceClientProtocol.AMQPS_WS)
            {
                WebSocketImpl webSocket = new WebSocketImpl();
                webSocket.configure(this.hostName, WEBSOCKET_PATH, AMQPS_WS_PORT, WEBSOCKET_SUB_PROTOCOL, null, null);
                ((TransportInternal)transport).addTransportLayer(webSocket);
            }

            transport.sasl().setMechanisms("ANONYMOUS");

            SslDomain domain = makeDomain();
            domain.setPeerAuthentication(SslDomain.VerifyMode.VERIFY_PEER);
            transport.ssl(domain);

            if (this.proxyOptions != null)
            {
                addProxyLayer(transport, this.hostName);
            }
        }
    }

    @Override
    public void onConnectionInit(Event event)
    {
        Connection conn = event.getConnection();
        conn.setHostname(hostName);
        log.debug("Opening connection for amqp cloud to device message sender");
        conn.open();
    }

    @Override
    public void onLinkRemoteOpen(Event event)
    {
        super.onLinkRemoteOpen(event);
        this.linkOpenedRemotely = true;
    }

    @Override
    public void onSessionRemoteOpen(Event event)
    {
        super.onSessionRemoteOpen(event);
        this.sessionOpenedRemotely = true;
    }

    @Override
    public void onConnectionRemoteOpen(Event event)
    {
        super.onConnectionRemoteOpen(event);
        this.connection = event.getConnection();
        this.connectionOpenedRemotely = true;

        Session cbsSession = event.getConnection().session();
        cbsSessionHandler = new CbsSessionHandler(cbsSession, this, this.authenticationTokenProvider, this.authorizationType);
    }

    /**
     * If an exception was encountered while opening the AMQP connection, this function shall throw that saved exception
     * @throws IOException if an exception was encountered while openinging the AMQP connection. The encountered
     * exception will be the inner exception
     */
    protected void verifyConnectionWasOpened() throws IOException
    {
        if (this.protonJExceptionParser != null)
        {
            throw new IOException("Encountered exception during amqp connection: " + protonJExceptionParser.getError() + " with description " + protonJExceptionParser.getErrorDescription());
        }

        if (this.savedException != null)
        {
            throw new IOException("Connection failed to be established", this.savedException);
        }

        if (!this.connectionOpenedRemotely || !this.sessionOpenedRemotely || !this.linkOpenedRemotely)
        {
            throw new IOException("Amqp connection timed out waiting for service to respond");
        }
    }

    /**
     * Create Proton SslDomain object from Address using the given Ssl mode
     * @return The created Ssl domain
     */
    private SslDomain makeDomain()
    {
        SslDomain domain = Proton.sslDomain();

        try
        {
            if (this.sslContext == null)
            {
                // Need the base trusted certs for IotHub in our ssl context. IotHubSSLContext handles that
                domain.setSslContext(new IotHubSSLContext().getSSLContext());
            }
            else
            {
                // Custom SSLContext set by user from service client options
                domain.setSslContext(this.sslContext);
            }
        }
        catch (Exception e)
        {
            this.savedException = e;
        }

        domain.init(SslDomain.Mode.CLIENT);

        return domain;
    }

    private void addProxyLayer(Transport transport, String hostName)
    {
        log.trace("Adding proxy layer to amqp_ws connection");
        ProxyImpl proxy = new ProxyImpl();
        final ProxyHandler proxyHandler = new ProxyHandlerImpl();
        proxy.configure(hostName + ":" + AmqpConnectionHandler.AMQPS_WS_PORT, null, proxyHandler, transport);
        ((TransportInternal) transport).addTransportLayer(proxy);
    }

    @Override
    public void onAuthenticationFailed(Exception e)
    {
        this.savedException = new IOException("Failed to authenticate connection", e);
    }
}
