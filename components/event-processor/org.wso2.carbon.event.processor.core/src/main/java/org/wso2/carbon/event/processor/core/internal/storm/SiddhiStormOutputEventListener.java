/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.wso2.carbon.event.processor.core.internal.storm;

import org.apache.log4j.Logger;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.wso2.carbon.databridge.commons.thrift.utils.HostAddressFinder;
import org.wso2.carbon.event.processor.core.ExecutionPlanConfiguration;
import org.wso2.carbon.event.processor.core.internal.listener.SiddhiOutputStreamListener;
import org.wso2.carbon.event.processor.storm.common.config.StormDeploymentConfig;
import org.wso2.carbon.event.processor.storm.common.manager.service.StormManagerService;
import org.wso2.carbon.event.processor.storm.common.transport.server.StreamCallback;
import org.wso2.carbon.event.processor.storm.common.transport.server.TCPEventServer;
import org.wso2.carbon.event.processor.storm.common.transport.server.TCPEventServerConfig;
import org.wso2.carbon.event.processor.storm.common.util.StormUtils;
import org.wso2.siddhi.query.api.definition.StreamDefinition;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Receives events from the Event publisher bolt running on storm. There will be one SiddhiStormOutputEventListener instance
 * per execution plan per tenant (all exported streams of execution plan are handled form a single instance). When events are
 * received from storm, the event  will be directed to the relevant output stream listener depending on the stream to forward
 * the event to the relevant output adaptor for the stream.
 */
public class SiddhiStormOutputEventListener implements StreamCallback {
    private static Logger log = Logger.getLogger(SiddhiStormOutputEventListener.class);
    private ExecutionPlanConfiguration executionPlanConfiguration;
    private int listeningPort;
    private int tenantId;
    private final StormDeploymentConfig stormDeploymentConfig;
    private String thisHostIp;
    private HashMap<String, SiddhiOutputStreamListener> streamNameToOutputStreamListenerMap = new HashMap<String, SiddhiOutputStreamListener>();
    private TCPEventServer TCPEventServer;
    private String logPrefix = "";

    public SiddhiStormOutputEventListener(ExecutionPlanConfiguration executionPlanConfiguration, int tenantId, StormDeploymentConfig stormDeploymentConfig) {
        this.executionPlanConfiguration = executionPlanConfiguration;
        this.tenantId = tenantId;
        this.stormDeploymentConfig = stormDeploymentConfig;
        init();
    }

    private void init() {
        logPrefix = "[CEP Publisher] for execution plan '" + executionPlanConfiguration.getName() + "'" + "(TenantID=" + tenantId + ") ";
        log.info(logPrefix + "Initializing storm output event listener");

        try {
            listeningPort = findPort();
            thisHostIp = HostAddressFinder.findAddress("localhost");
            TCPEventServer = new TCPEventServer(new TCPEventServerConfig(listeningPort), this);
            TCPEventServer.start();
            Thread thread = new Thread(new Registrar());
            thread.start();
        } catch (Exception e) {
            log.error(logPrefix + "Failed to start event listener", e);
        }
    }


    public void registerOutputStreamListener(StreamDefinition siddhiStreamDefinition, SiddhiOutputStreamListener outputStreamListener) {
        log.info(logPrefix + "Registering output stream listener for Siddhi stream : " + siddhiStreamDefinition.getStreamId());
        streamNameToOutputStreamListenerMap.put(siddhiStreamDefinition.getStreamId(), outputStreamListener);
        TCPEventServer.subscribe(siddhiStreamDefinition);
    }

    @Override
    public void receive(String streamId, Object[] event) {
        SiddhiOutputStreamListener outputStreamListener = streamNameToOutputStreamListenerMap.get(streamId);
        if (outputStreamListener != null) {
            outputStreamListener.sendEventData(event);
        } else {
            log.warn("Cannot find output event listener for stream " + streamId + " in execution plan " + executionPlanConfiguration.getName()
                    + " of tenant " + tenantId + ". Discarding event:" + Arrays.deepToString(event));
        }
    }

    private int findPort() throws Exception {
        for (int i = stormDeploymentConfig.getTransportMinPort(); i <= stormDeploymentConfig.getTransportMaxPort(); i++) {
            if (!StormUtils.isPortUsed(i)) {
                return i;
            }
        }
        throw new Exception("Cannot find free port in range " + stormDeploymentConfig.getTransportMinPort() + "~" + stormDeploymentConfig.getTransportMaxPort());
    }


    class Registrar implements Runnable {

        /**
         * When an object implementing interface <code>Runnable</code> is used
         * to create a thread, starting the thread causes the object's
         * <code>run</code> method to be called in that separately executing
         * thread.
         * <p/>
         * The general contract of the method <code>run</code> is that it may
         * take any action whatsoever.
         *
         * @see Thread#run()
         */
        @Override
        public void run() {
            log.info(logPrefix + "Registering CEP publisher with " + thisHostIp + ":" + listeningPort);
            while (!registerCEPPublisherWithStormMangerService()) {
                log.info(logPrefix + "Retry registering CEP publisher in 30 sec");
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e1) {
                    //ignore
                }
            }

        }

        private boolean registerCEPPublisherWithStormMangerService() {

            TTransport transport = null;
            try {
                transport = new TSocket(stormDeploymentConfig.getManagers().get(0).getHostName(), stormDeploymentConfig.getManagers().get(0).getPort());
                TProtocol protocol = new TBinaryProtocol(transport);
                transport.open();

                StormManagerService.Client client = new StormManagerService.Client(protocol);
                client.registerCEPPublisher(tenantId, executionPlanConfiguration.getName(), thisHostIp, listeningPort);
                log.info(logPrefix + "Successfully registering CEP publisher with " + thisHostIp + ":" + listeningPort);
                return true;
            } catch (Exception e) {
                log.error(logPrefix + "Error in registering CEP publisher", e);
                return false;
            } finally {
                if (transport != null) {
                    transport.close();
                }
            }
        }
    }
}
