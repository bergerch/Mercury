/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.demo.microbenchmarks;

import bftsmart.tom.MessageContext;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.server.defaultservices.CommandsInfo;
import bftsmart.tom.server.defaultservices.DefaultRecoverable;
import bftsmart.tom.util.Storage;
import bftsmart.tom.util.TOMUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple server that just acknowledge the reception of a request.
 */
public final class ThroughputLatencyServer extends DefaultRecoverable{
    
    private int interval;
    private byte[] reply;
    private float maxTp = -1;
    private boolean context;
    private int signed;
    
    private byte[] state;
    
    private int iterations = 0;
    private long throughputMeasurementStartTime = System.currentTimeMillis();
            
    private Storage totalLatency = null;
    private Storage consensusLatency = null;
    private Storage preConsLatency = null;
    private Storage posConsLatency = null;
    private Storage proposeLatency = null;
    private Storage writeLatency = null;
    private Storage acceptLatency = null;
    
    private Storage batchSize = null;
    
    private ServiceReplica replica;
    
    private RandomAccessFile randomAccessFile = null;
    private FileChannel channel = null;

    long[] decisionTimes;
    int decisionCounter = 0;

    public ThroughputLatencyServer(int id, int interval, int replySize, int stateSize, boolean context,  int signed, int write) {

        this.interval = interval;
        this.context = context;
        this.signed = signed;
        
        this.reply = new byte[replySize];
        
        for (int i = 0; i < replySize ;i++)
            reply[i] = (byte) i;
        
        this.state = new byte[stateSize];
        
        for (int i = 0; i < stateSize ;i++)
            state[i] = (byte) i;

        totalLatency = new Storage(interval);
        consensusLatency = new Storage(interval);
        preConsLatency = new Storage(interval);
        posConsLatency = new Storage(interval);
        proposeLatency = new Storage(interval);
        writeLatency = new Storage(interval);
        acceptLatency = new Storage(interval);

        decisionTimes = new long[100000];
        
        batchSize = new Storage(interval);
        
        if (write > 0) {
            
            try {
                final File f = File.createTempFile("bft-"+id+"-", Long.toString(System.nanoTime()));
                randomAccessFile = new RandomAccessFile(f, (write > 1 ? "rwd" : "rw"));
                channel = randomAccessFile.getChannel();
                
                Runtime.getRuntime().addShutdownHook(new Thread() {
                    
                    @Override
                    public void run() {
                        
                        f.delete();
                    }
                });
            } catch (IOException ex) {
                ex.printStackTrace();
                System.exit(0);
            }
        }
        replica = new ServiceReplica(id, this, this);
    }
    
    @Override
    public byte[][] appExecuteBatch(byte[][] commands, MessageContext[] msgCtxs, boolean fromConsensus) {
        
        batchSize.store(commands.length);
                
        byte[][] replies = new byte[commands.length][];
        
        for (int i = 0; i < commands.length; i++) {
            
            replies[i] = execute(commands[i],msgCtxs[i]);
            
        }
        
        if (randomAccessFile != null) {
                
            ObjectOutputStream oos = null;
            try {
                CommandsInfo cmd = new CommandsInfo(commands,msgCtxs);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                oos = new ObjectOutputStream(bos);
                oos.writeObject(cmd);
                oos.flush();
                byte[] bytes = bos.toByteArray();
                oos.close();
                bos.close();
                
                ByteBuffer bb = ByteBuffer.allocate(bytes.length);
                bb.put(bytes);
                bb.flip();
                
                channel.write(bb);
                channel.force(false);
            } catch (IOException ex) {
                Logger.getLogger(ThroughputLatencyServer.class.getName()).log(Level.SEVERE, null, ex);
                
            } finally {
                try {
                    oos.close();
                } catch (IOException ex) {
                    Logger.getLogger(ThroughputLatencyServer.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
                    
        return replies;
    }
    
    @Override
    public byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx) {
        return execute(command,msgCtx);
    }
    
    public byte[] execute(byte[] command, MessageContext msgCtx) {
        
        ByteBuffer buffer = ByteBuffer.wrap(command);
        int l = buffer.getInt();
        byte[] request = new byte[l];
        buffer.get(request);
        l = buffer.getInt();
        byte[] signature = new byte[l];
        
        buffer.get(signature);
        Signature eng;
        
        try {
            
            if (signed > 0) {
                
                if (signed == 1) {
                    
                    eng = TOMUtil.getSigEngine();
                    eng.initVerify(replica.getReplicaContext().getStaticConfiguration().getPublicKey());
                } else {
                
                    eng = Signature.getInstance("SHA256withECDSA", "SunEC");
                    Base64.Decoder b64 = Base64.getDecoder();
                    CertificateFactory kf = CertificateFactory.getInstance("X.509");
                
                    byte[] cert = b64.decode(ThroughputLatencyClient.pubKey);
                    InputStream certstream = new ByteArrayInputStream (cert);
                
                    eng.initVerify(kf.generateCertificate(certstream));
                    
                }
                eng.update(request);
                if (!eng.verify(signature)) {
                    
                    System.out.println("Client sent invalid signature!");
                    System.exit(0);
                }
            }
            
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException | CertificateException ex) {
            ex.printStackTrace();
            System.exit(0);
        } catch (NoSuchProviderException ex) {
            ex.printStackTrace();
            System.exit(0);
        }
        
        boolean readOnly = false;
        
        iterations++;

        if (msgCtx != null && msgCtx.getFirstInBatch() != null) {


            readOnly = msgCtx.readOnly;
                    
            msgCtx.getFirstInBatch().executedTime = System.nanoTime();
                        
            totalLatency.store(msgCtx.getFirstInBatch().executedTime - msgCtx.getFirstInBatch().receptionTime);

            if (readOnly == false) {

                decisionTimes[decisionCounter] = System.currentTimeMillis();
                consensusLatency.store(msgCtx.getFirstInBatch().decisionTime - msgCtx.getFirstInBatch().consensusStartTime);
                long temp = msgCtx.getFirstInBatch().consensusStartTime - msgCtx.getFirstInBatch().receptionTime;
                preConsLatency.store(temp > 0 ? temp : 0);
                posConsLatency.store(msgCtx.getFirstInBatch().executedTime - msgCtx.getFirstInBatch().decisionTime);            
                proposeLatency.store(msgCtx.getFirstInBatch().writeSentTime - msgCtx.getFirstInBatch().consensusStartTime);
                writeLatency.store(msgCtx.getFirstInBatch().acceptSentTime - msgCtx.getFirstInBatch().writeSentTime);
                acceptLatency.store(msgCtx.getFirstInBatch().decisionTime - msgCtx.getFirstInBatch().acceptSentTime);

                /*
                String line = "\n";
                if (decisionCounter == 0) {
                    line += "Time, Consensus Latency, Configuration \n";
                }

                line += System.currentTimeMillis() + ", " + (msgCtx.getFirstInBatch().decisionTime - msgCtx.getFirstInBatch().consensusStartTime) + ", " +
                        replica.getReplicaContext().getSVController().tomLayer.execManager.getCurrentLeader() + "," +
                        replica.getReplicaContext().getCurrentView().getViewString() + ",";


                Writer output;
                try {
                    output = new BufferedWriter(new FileWriter("measurement-server" + replica.getId(), true));
                    output.append(line);
                    output.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }

                decisionCounter++;
                */

            } else {
            
           
                consensusLatency.store(0);
                preConsLatency.store(0);
                posConsLatency.store(0);            
                proposeLatency.store(0);
                writeLatency.store(0);
                acceptLatency.store(0);
                
                
            }
            
        } else {
            
            
                consensusLatency.store(0);
                preConsLatency.store(0);
                posConsLatency.store(0);            
                proposeLatency.store(0);
                writeLatency.store(0);
                acceptLatency.store(0);
                
               
        }
        
        float tp = -1;


        if(iterations % interval == 0) {
            if (context) System.out.println("--- (Context)  iterations: "+ iterations + " // regency: " + msgCtx.getRegency() + " // consensus: " + msgCtx.getConsensusId() + " ---");

            String line = "" + replica.getReplicaContext().getCurrentView().getN() + ", " +
                    replica.getReplicaContext().getCurrentView().getF() + ", " +
                    replica.getReplicaContext().getSVController().tomLayer.execManager.getCurrentLeader() + "," +
                    replica.getReplicaContext().getCurrentView().getViewString() + ",";
            System.out.println("--- Measurements after "+ iterations+" ops ("+interval+" samples) ---");
            
            tp = (float)(interval*1000/(float)(System.currentTimeMillis()-throughputMeasurementStartTime));
            
            if (tp > maxTp) maxTp = tp;
            
            System.out.println("Throughput = " + tp +" operations/sec (Maximum observed: " + maxTp + " ops/sec)");
            line += tp + ", ";
            line += maxTp + ", ";
            System.out.println("Total latency = " + totalLatency.getAverage(false) / 1000 + " (+/- "+ (long)totalLatency.getDP(false) / 1000 +") us ");
            line += totalLatency.getAverage(false) / 1000 + ",";
            line += (long)totalLatency.getDP(false) / 1000 + ",";
            totalLatency.reset();
            System.out.println("Consensus latency = " + consensusLatency.getAverage(false) / 1000 + " (+/- "+ (long)consensusLatency.getDP(false) / 1000 +") us ");
            line +=  consensusLatency.getAverage(false) / 1000 + ",";
            line += (long)consensusLatency.getDP(false) / 1000 + ",";
            consensusLatency.reset();
            System.out.println("Pre-consensus latency = " + preConsLatency.getAverage(false) / 1000 + " (+/- "+ (long)preConsLatency.getDP(false) / 1000 +") us ");
            line +=  preConsLatency.getAverage(false) / 1000 + ",";
            line +=  (long)preConsLatency.getDP(false) / 1000 + ",";
            preConsLatency.reset();
            System.out.println("Pos-consensus latency = " + posConsLatency.getAverage(false) / 1000 + " (+/- "+ (long)posConsLatency.getDP(false) / 1000 +") us ");
            line += posConsLatency.getAverage(false) / 1000 + ",";
            line += (long)posConsLatency.getDP(false) / 1000 + ",";
            posConsLatency.reset();
            System.out.println("Propose latency = " + proposeLatency.getAverage(false) / 1000 + " (+/- "+ (long)proposeLatency.getDP(false) / 1000 +") us ");
            line += proposeLatency.getAverage(false) / 1000 + "," + (long)proposeLatency.getDP(false) / 1000 + ",";
            proposeLatency.reset();
            System.out.println("Write latency = " + writeLatency.getAverage(false) / 1000 + " (+/- "+ (long)writeLatency.getDP(false) / 1000 +") us ");
            line += writeLatency.getAverage(false) / 1000 + "," + (long)writeLatency.getDP(false) / 1000 + ",";
            writeLatency.reset();
            System.out.println("Accept latency = " + acceptLatency.getAverage(false) / 1000 + " (+/- "+ (long)acceptLatency.getDP(false) / 1000 +") us ");
            line += acceptLatency.getAverage(false) / 1000 + "," + (long)acceptLatency.getDP(false) / 1000 + ",";
            acceptLatency.reset();
            
            System.out.println("Batch average size = " + batchSize.getAverage(false) + " (+/- "+ (long)batchSize.getDP(false) +") requests");
            batchSize.reset();
            
            throughputMeasurementStartTime = System.currentTimeMillis();


            line += "\n";
            Writer output;
            try {
                output = new BufferedWriter(new FileWriter("measurement-server" + replica.getId(), true));
                output.append(line);
                output.close();

            } catch (IOException e) {
                e.printStackTrace();
            }



        }


        return reply;
    }

    public static void main(String[] args){
        if(args.length < 6) {
            System.out.println("Usage: ... ThroughputLatencyServer <processId> <measurement interval> <reply size> <state size> <context?> <nosig | default | ecdsa> [rwd | rw]");
            System.exit(-1);
        }

        int processId = Integer.parseInt(args[0]);
        int interval = Integer.parseInt(args[1]);
        int replySize = Integer.parseInt(args[2]);
        int stateSize = Integer.parseInt(args[3]);
        boolean context = Boolean.parseBoolean(args[4]);
        String signed = args[5];
        String write = args.length > 6 ? args[6] : "";
        
        int s = 0;
        
        if (!signed.equalsIgnoreCase("nosig")) s++;
        if (signed.equalsIgnoreCase("ecdsa")) s++;
        
        if (s == 2 && Security.getProvider("SunEC") == null) {
            
            System.out.println("Option 'ecdsa' requires SunEC provider to be available.");
            System.exit(0);
        }
        
        int w = 0;
        
        if (!write.equalsIgnoreCase("")) w++;
        if (write.equalsIgnoreCase("rwd")) w++;

        ThroughputLatencyServer server = new ThroughputLatencyServer(processId,interval,replySize, stateSize, context, s, w);

        Thread clock = new Thread(){ // print time each 30s
            @Override
            public void run() {
                long initTime = System.currentTimeMillis();
                long time_passed = 0;
                while (true) {
                    time_passed = (System.currentTimeMillis()-initTime)/1000;
                    System.out.println("#########################################################\n"+
                                       "           --> TIME PASSED : " + time_passed + "s\n"+
                                       "#########################################################");
                    try {
                        sleep(30000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } 
        };
        clock.start();
    }

    @Override
    public void installSnapshot(byte[] state) {
        //nothing
    }

    @Override
    public byte[] getSnapshot() {
        return this.state;
    }

   
}
