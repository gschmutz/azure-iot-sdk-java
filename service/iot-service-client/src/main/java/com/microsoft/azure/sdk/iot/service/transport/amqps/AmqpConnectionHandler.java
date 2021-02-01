/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.service.transport.amqps;

import com.azure.core.amqp.implementation.CbsAuthorizationType;
import com.azure.core.credential.TokenCredential;
import com.microsoft.azure.proton.transport.proxy.ProxyHandler;
import com.microsoft.azure.proton.transport.proxy.impl.ProxyHandlerImpl;
import com.microsoft.azure.proton.transport.proxy.impl.ProxyImpl;
import com.microsoft.azure.sdk.iot.deps.auth.IotHubSSLContext;
import com.microsoft.azure.sdk.iot.deps.transport.amqp.ErrorLoggingBaseHandlerWithCleanup;
import com.microsoft.azure.sdk.iot.deps.ws.impl.WebSocketImpl;
import com.microsoft.azure.sdk.iot.service.IotHubServiceClientProtocol;
import com.microsoft.azure.sdk.iot.service.ProxyOptions;
import com.microsoft.azure.sdk.iot.service.Tools;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubException;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Event;
import org.apache.qpid.proton.engine.Session;
import org.apache.qpid.proton.engine.SslDomain;
import org.apache.qpid.proton.engine.Transport;
import org.apache.qpid.proton.engine.impl.TransportInternal;
import org.apache.qpid.proton.reactor.FlowController;
import org.apache.qpid.proton.reactor.Handshaker;
import org.apache.qpid.proton.reactor.Reactor;

import javax.net.ssl.SSLContext;
import java.io.IOException;

@Slf4j
public abstract class AmqpConnectionHandler extends ErrorLoggingBaseHandlerWithCleanup implements CbsSessionStateCallback
{
    private static final String WEBSOCKET_PATH = "/$iothub/websocket";
    private static final String WEBSOCKET_SUB_PROTOCOL = "AMQPWSB10";
    private static final int AMQPS_PORT = 5671;
    private static final int AMQPS_WS_PORT = 443;

    private Exception savedException;
    private boolean connectionOpenedRemotely;
    private boolean sessionOpenedRemotely;
    private boolean linkOpenedRemotely;

    protected final String hostName;
    protected String userName;
    protected String sasToken;
    protected TokenCredential authenticationTokenProvider;
    protected CbsAuthorizationType authorizationType;
    protected final IotHubServiceClientProtocol iotHubServiceClientProtocol;
    protected final ProxyOptions proxyOptions;
    protected final SSLContext sslContext;

    protected Connection connection;

    protected AmqpConnectionHandler(
            String hostName,
            String userName,
            String sasToken,
            IotHubServiceClientProtocol iotHubServiceClientProtocol,
            ProxyOptions proxyOptions,
            SSLContext sslContext)
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

        // Enables proton-j to automatically mirror the local state of the client with the remote state. For instance,
        // if the service closes a session, this handshaker will automatically close the session locally as well.
        add(new Handshaker());

        // Enables proton-j to automatically give link credit back to the service on all the client side receiver links
        // after successfully processing a message.
        add(new FlowController());
    }

    protected AmqpConnectionHandler(
            String hostName,
            TokenCredential authenticationTokenProvider,
            CbsAuthorizationType authorizationType,
            IotHubServiceClientProtocol iotHubServiceClientProtocol,
            ProxyOptions proxyOptions,
            SSLContext sslContext)
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

        // Enables proton-j to automatically mirror the local state of the client with the remote state. For instance,
        // if the service closes a session, this handshaker will automatically close the session locally as well.
        add(new Handshaker());

        // Enables proton-j to automatically give link credit back to the service on all the client side receiver links
        // after successfully processing a message.
        add(new FlowController());
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

            // Note that this does not mean that the connection will not be authenticated. This simply defers authentication
            // to the claims based security model that IoT Hub implements wherein the client sends the authentication token
            // over the CBS link rather than doing a sasl.plain(username, password) call at this point.
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
        log.debug("Opening AMQP connection");
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

        // Once the connection opens, get that connection and make it create a new session that will serve as the CBS
        // session where authentication will take place.
        Session cbsSession = event.getConnection().session();
        new CbsSessionHandler(
                cbsSession,
                this,
                this.authenticationTokenProvider,
                this.authorizationType);
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
    public void onAuthenticationFailed(IotHubException e)
    {
        this.savedException = e;
    }
}
