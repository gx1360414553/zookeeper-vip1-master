/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.ZooKeeperThread;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implementation of leader election using TCP. It uses an object of the class
 * QuorumCnxManager to manage connections. Otherwise, the algorithm is push-based
 * as with the other UDP implementations.
 *
 * There are a few parameters that can be tuned to change its behavior. First,
 * finalizeWait determines the amount of time to wait until deciding upon a leader.
 * This is part of the leader election algorithm.
 */


public class FastLeaderElection implements Election {
    private static final Logger LOG = LoggerFactory.getLogger(FastLeaderElection.class);

    /**
     * Determine how much time a process has to wait
     * once it believes that it has reached the end of
     * leader election.
     */
    final static int finalizeWait = 200;


    /**
     * Upper bound on the amount of time between two consecutive
     * notification checks. This impacts the amount of time to get
     * the system up again after long partitions. Currently 60 seconds.
     */

    final static int maxNotificationInterval = 60000;

    /**
     * Connection manager. Fast leader election uses TCP for
     * communication between peers, and QuorumCnxManager manages
     * such connections.
     */

    QuorumCnxManager manager;


    /**
     * Notifications are messages that let other peers know that
     * a given peer has changed its vote, either because it has
     * joined leader election or because it learned of another
     * peer with higher zxid or same zxid and higher server id
     */

    static public class Notification {
        /*
         * Format version, introduced in 3.4.6
         */

        public final static int CURRENTVERSION = 0x1;
        int version;

        /*
         * Proposed leader
         */
        long leader;

        /*
         * zxid of the proposed leader
         */
        long zxid;

        /*
         * Epoch
         */
        long electionEpoch;

        /*
         * current state of sender
         */
        QuorumPeer.ServerState state;

        /*
         * Address of sender
         */
        long sid;

        /*
         * epoch of the proposed leader
         */
        long peerEpoch;

        @Override
        public String toString() {
            return Long.toHexString(version) + " (message format version), "
                    + leader + " (n.leader), 0x"
                    + Long.toHexString(zxid) + " (n.zxid), 0x"
                    + Long.toHexString(electionEpoch) + " (n.round), " + state
                    + " (n.state), " + sid + " (n.sid), 0x"
                    + Long.toHexString(peerEpoch) + " (n.peerEpoch) ";
        }
    }

    static ByteBuffer buildMsg(int state,
            long leader,
            long zxid,
            long electionEpoch,
            long epoch) {
        byte requestBytes[] = new byte[40];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);

        /*
         * Building notification packet to send
         */

        requestBuffer.clear();
        requestBuffer.putInt(state);
        requestBuffer.putLong(leader);
        requestBuffer.putLong(zxid);
        requestBuffer.putLong(electionEpoch);
        requestBuffer.putLong(epoch);
        requestBuffer.putInt(Notification.CURRENTVERSION);

        return requestBuffer;
    }

    /**
     * Messages that a peer wants to send to other peers.
     * These messages can be both Notifications and Acks
     * of reception of notification.
     */
    static public class ToSend {
        static enum mType {crequest, challenge, notification, ack}

        ToSend(mType type,
                long leader,
                long zxid,
                long electionEpoch,
                ServerState state,
                long sid,
                long peerEpoch) {

            this.leader = leader;
            this.zxid = zxid;
            this.electionEpoch = electionEpoch;
            this.state = state;
            this.sid = sid;
            this.peerEpoch = peerEpoch;
        }

        /*
         * Proposed leader in the case of notification
         */
        long leader;

        /*
         * id contains the tag for acks, and zxid for notifications
         */
        long zxid;

        /*
         * Epoch
         */
        long electionEpoch;

        /*
         * Current state;
         */
        QuorumPeer.ServerState state;

        /*
         * Address of recipient
         */
        long sid;

        /*
         * Leader epoch
         */
        long peerEpoch;
    }

    LinkedBlockingQueue<ToSend> sendqueue;
    LinkedBlockingQueue<Notification> recvqueue;

    /**
     * Multi-threaded implementation of message handler. Messenger
     * implements two sub-classes: WorkReceiver and  WorkSender. The
     * functionality of each is obvious from the name. Each of these
     * spawns a new thread.
     */

    protected class Messenger {

        /**
         * Receives messages from instance of QuorumCnxManager on
         * method run(), and processes such messages.
         */

        class WorkerReceiver extends ZooKeeperThread {
            volatile boolean stop;
            QuorumCnxManager manager;

            WorkerReceiver(QuorumCnxManager manager) {
                super("WorkerReceiver");
                this.stop = false;
                this.manager = manager;
            }

            public void run() {

                Message response;
                while (!stop) {
                    // Sleeps on receive
                    try{
                        response = manager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
                        if(response == null) continue;

                        /*
                         * If it is from an observer, respond right away.
                         * Note that the following predicate assumes that
                         * if a server is not a follower, then it must be
                         * an observer. If we ever have any other type of
                         * learner in the future, we'll have to change the
                         * way we check for observers.
                         */
                        if(!validVoter(response.sid)){
                            // 不是合法的投票者，那么它就是observer，把自己当前的投票告诉他
                            Vote current = self.getCurrentVote();
                            ToSend notmsg = new ToSend(ToSend.mType.notification,
                                    current.getId(),
                                    current.getZxid(),
                                    logicalclock.get(),
                                    self.getPeerState(),
                                    response.sid,
                                    current.getPeerEpoch());

                            sendqueue.offer(notmsg);
                        } else {
                            // Receive new message
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Receive new notification message. My id = "
                                        + self.getId());
                            }

                            /*
                             * We check for 28 bytes for backward compatibility
                             */
                            if (response.buffer.capacity() < 28) {
                                LOG.error("Got a short response: "
                                        + response.buffer.capacity());
                                continue;
                            }
                            boolean backCompatibility = (response.buffer.capacity() == 28);
                            response.buffer.clear();

                            // Instantiate Notification and set its attributes
                            Notification n = new Notification();

                            // State of peer that sent this message
                            // 对方的服务器状态
                            QuorumPeer.ServerState ackstate = QuorumPeer.ServerState.LOOKING;
                            switch (response.buffer.getInt()) {
                            case 0:
                                ackstate = QuorumPeer.ServerState.LOOKING;
                                break;
                            case 1:
                                ackstate = QuorumPeer.ServerState.FOLLOWING;
                                break;
                            case 2:
                                ackstate = QuorumPeer.ServerState.LEADING;
                                break;
                            case 3:
                                ackstate = QuorumPeer.ServerState.OBSERVING;
                                break;
                            default:
                                continue;
                            }

                            n.leader = response.buffer.getLong();
                            n.zxid = response.buffer.getLong();
                            n.electionEpoch = response.buffer.getLong();
                            n.state = ackstate;
                            n.sid = response.sid;
                            if(!backCompatibility){
                                n.peerEpoch = response.buffer.getLong();
                            } else {
                                if(LOG.isInfoEnabled()){
                                    LOG.info("Backward compatibility mode, server id=" + n.sid);
                                }
                                n.peerEpoch = ZxidUtils.getEpochFromZxid(n.zxid);
                            }

                            /*
                             * Version added in 3.4.6
                             */

                            n.version = (response.buffer.remaining() >= 4) ?
                                         response.buffer.getInt() : 0x0;

                            /*
                             * Print notification info
                             */
                            if(LOG.isInfoEnabled()){
                                printNotification(n);
                            }

                            /*
                             * If this server is looking, then send proposed leader
                             */
                            // 如果自己是LOOKING状态，代表正在进行领导选举
                            if(self.getPeerState() == QuorumPeer.ServerState.LOOKING){
                                recvqueue.offer(n);

                                /*
                                 * Send a notification back if the peer that sent this
                                 * message is also looking and its logical clock is
                                 * lagging behind.
                                 */
                                if((ackstate == QuorumPeer.ServerState.LOOKING)
                                        && (n.electionEpoch < logicalclock.get())){
                                    Vote v = getVote();
                                    ToSend notmsg = new ToSend(ToSend.mType.notification,
                                            v.getId(),
                                            v.getZxid(),
                                            logicalclock.get(),
                                            self.getPeerState(),
                                            response.sid,
                                            v.getPeerEpoch());
                                    sendqueue.offer(notmsg);
                                }
                            } else {
                                /*
                                 * If this server is not looking, but the one that sent the ack
                                 * is looking, then send back what it believes to be the leader.
                                 */
                                // 如果自己现在不是LOOKING状态, 那么有可能自己是Leader，或自己是Follower
                                // 那么就把他认为的Leader作为投票发送出去

                                Vote current = self.getCurrentVote();
                                if(ackstate == QuorumPeer.ServerState.LOOKING){
                                    if(LOG.isDebugEnabled()){
                                        LOG.debug("Sending new notification. My id =  " +
                                                self.getId() + " recipient=" +
                                                response.sid + " zxid=0x" +
                                                Long.toHexString(current.getZxid()) +
                                                " leader=" + current.getId());
                                    }

                                    ToSend notmsg;
                                    if(n.version > 0x0) {
                                        notmsg = new ToSend(
                                                ToSend.mType.notification,
                                                current.getId(),
                                                current.getZxid(),
                                                current.getElectionEpoch(),
                                                self.getPeerState(),
                                                response.sid,
                                                current.getPeerEpoch());

                                    } else {
                                        Vote bcVote = self.getBCVote();
                                        notmsg = new ToSend(
                                                ToSend.mType.notification,
                                                bcVote.getId(),
                                                bcVote.getZxid(),
                                                bcVote.getElectionEpoch(),
                                                self.getPeerState(),
                                                response.sid,
                                                bcVote.getPeerEpoch());
                                    }
                                    sendqueue.offer(notmsg);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted Exception while waiting for new message" +
                                e.toString());
                    }
                }
                LOG.info("WorkerReceiver is down");
            }
        }


        /**
         * This worker simply dequeues a message to send and
         * and queues it on the manager's queue.
         */

        class WorkerSender extends ZooKeeperThread {
            volatile boolean stop;
            QuorumCnxManager manager;

            WorkerSender(QuorumCnxManager manager){
                super("WorkerSender");
                this.stop = false;
                this.manager = manager;
            }

            public void run() {
                while (!stop) {
                    try {
                        ToSend m = sendqueue.poll(3000, TimeUnit.MILLISECONDS);
                        if(m == null) continue;

                        process(m);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                LOG.info("WorkerSender is down");
            }

            /**
             * Called by run() once there is a new message to send.
             *
             * @param m     message to send
             */
            void process(ToSend m) {
                ByteBuffer requestBuffer = buildMsg(m.state.ordinal(),
                                                        m.leader,
                                                        m.zxid,
                                                        m.electionEpoch,
                                                        m.peerEpoch);
                manager.toSend(m.sid, requestBuffer);
            }
        }


        WorkerSender ws;
        WorkerReceiver wr;

        /**
         * Constructor of class Messenger.
         *
         * @param manager   Connection manager
         */
        Messenger(QuorumCnxManager manager) {

            this.ws = new WorkerSender(manager);//线程

            Thread t = new Thread(this.ws,
                    "WorkerSender[myid=" + self.getId() + "]");
            t.setDaemon(true);
            t.start();

            this.wr = new WorkerReceiver(manager);//线程

            t = new Thread(this.wr,
                    "WorkerReceiver[myid=" + self.getId() + "]");
            t.setDaemon(true);
            t.start();
        }

        /**
         * Stops instances of WorkerSender and WorkerReceiver
         */
        void halt(){
            this.ws.stop = true;
            this.wr.stop = true;
        }

    }

    QuorumPeer self;
    Messenger messenger;
    AtomicLong logicalclock = new AtomicLong(); /* Election instance *///领导选举次数
    long proposedLeader;
    long proposedZxid;
    long proposedEpoch;


    /**
     * Returns the current vlue of the logical clock counter
     */
    public long getLogicalClock(){
        return logicalclock.get();
    }

    /**
     * Constructor of FastLeaderElection. It takes two parameters, one
     * is the QuorumPeer object that instantiated this object, and the other
     * is the connection manager. Such an object should be created only once
     * by each peer during an instance of the ZooKeeper service.
     *
     * @param self  QuorumPeer that created this object
     * @param manager   Connection manager
     */
    public FastLeaderElection(QuorumPeer self, QuorumCnxManager manager){
        this.stop = false;
        this.manager = manager;
        // 初始化sendqueue、recvqueue 并且启动WorkerSender和WorkerReceiver线程
        starter(self, manager);
    }

    /**
     * This method is invoked by the constructor. Because it is a
     * part of the starting procedure of the object that must be on
     * any constructor of this class, it is probably best to keep as
     * a separate method. As we have a single constructor currently,
     * it is not strictly necessary to have it separate.
     *
     * @param self      QuorumPeer that created this object
     * @param manager   Connection manager
     */
    private void starter(QuorumPeer self, QuorumCnxManager manager) {
        this.self = self;
        proposedLeader = -1;
        proposedZxid = -1;

        sendqueue = new LinkedBlockingQueue<ToSend>();
        recvqueue = new LinkedBlockingQueue<Notification>();
        this.messenger = new Messenger(manager);
    }

    private void leaveInstance(Vote v) {
        if(LOG.isDebugEnabled()){
            LOG.debug("About to leave FLE instance: leader="
                + v.getId() + ", zxid=0x" +
                Long.toHexString(v.getZxid()) + ", my id=" + self.getId()
                + ", my state=" + self.getPeerState());
        }
        recvqueue.clear();
    }

    public QuorumCnxManager getCnxManager(){
        return manager;
    }

    volatile boolean stop;
    public void shutdown(){
        stop = true;
        LOG.debug("Shutting down connection manager");
        manager.halt();
        LOG.debug("Shutting down messenger");
        messenger.halt();
        LOG.debug("FLE is down");
    }


    /**
     * Send notifications to all peers upon a change in our vote
     * // 给所有参与投票的服务器发送notifications
     */
    private void sendNotifications() {
        for (QuorumServer server : self.getVotingView().values()) {
            long sid = server.id;//循环

            ToSend notmsg = new ToSend(ToSend.mType.notification,
                    proposedLeader,
                    proposedZxid,
                    logicalclock.get(),
                    QuorumPeer.ServerState.LOOKING,
                    sid,
                    proposedEpoch);
            if(LOG.isDebugEnabled()){
                LOG.debug("Sending Notification: " + proposedLeader + " (n.leader), 0x"  +
                      Long.toHexString(proposedZxid) + " (n.zxid), 0x" + Long.toHexString(logicalclock.get())  +
                      " (n.round), " + sid + " (recipient), " + self.getId() +
                      " (myid), 0x" + Long.toHexString(proposedEpoch) + " (n.peerEpoch)");
            }
            sendqueue.offer(notmsg);
        }
    }


    private void printNotification(Notification n){
        LOG.info("Notification: " + n.toString()
                + self.getPeerState() + " (my state)");
    }

    /**
     * Check if a pair (server id, zxid) succeeds our
     * current vote.
     *
     *
     */
    protected boolean totalOrderPredicate(long newId, long newZxid, long newEpoch, long curId, long curZxid, long curEpoch) {
        LOG.debug("id: " + newId + ", proposed id: " + curId + ", zxid: 0x" +
                Long.toHexString(newZxid) + ", proposed zxid: 0x" + Long.toHexString(curZxid));
        // getWeight这里QuorumMaj会固定返回1
        if(self.getQuorumVerifier().getWeight(newId) == 0){
            return false;
        }

        /*
         * We return true if one of the following three cases hold:
         * 1- New epoch is higher
         * 2- New epoch is the same as current epoch, but new zxid is higher
         * 3- New epoch is the same as current epoch, new zxid is the same
         *  as current zxid, but server id is higher.
         *
         *
         */
        //投票逻辑 先比较Epoch 选大的如果Epoch相等，比较Zxid选大的如果Zxid相等，比较myid选大的    如果是new比cur更优秀返回true
        return ((newEpoch > curEpoch) ||
                ((newEpoch == curEpoch) &&
                ((newZxid > curZxid) || ((newZxid == curZxid) && (newId > curId)))));
    }

    /**
     * Termination predicate. Given a set of votes, determines if
     * have sufficient to declare the end of the election round.
     *
     *  @param votes    Set of votes
     *
     */
    protected boolean termPredicate(
            HashMap<Long, Vote> votes,
            Vote vote) {

        HashSet<Long> set = new HashSet<Long>(); // sit

        /*
         * First make the views consistent. Sometimes peers will have
         * different zxids for a server depending on timing.
         * 过滤和我投同一台服务器的选票
         */
        for (Map.Entry<Long,Vote> entry : votes.entrySet()) {
            if (vote.equals(entry.getValue())){
                set.add(entry.getKey());  //sid
            }
        }

        // 看自己的投票的服务器的选票数量符不符合过半验证
        return self.getQuorumVerifier().containsQuorum(set);
    }

    /**
     * In the case there is a leader elected, and a quorum supporting
     * this leader, we have to check if the leader has voted and acked
     * that it is leading. We need this check to avoid that peers keep
     * electing over and over a peer that has crashed and it is no
     * longer leading.
     *
     * @param votes set of votes
     * @param   leader  leader id
     * @param   electionEpoch   epoch id
     */
    protected boolean checkLeader(
            HashMap<Long, Vote> votes,
            long leader,
            long electionEpoch){

        boolean predicate = true;

        /*
         * If everyone else thinks I'm the leader, I must be the leader.
         * The other two checks are just for the case in which I'm not the
         * leader. If I'm not the leader and I haven't received a message
         * from leader stating that it is leading, then predicate is false.
         */

        if(leader != self.getId()){
            if(votes.get(leader) == null) predicate = false;
            else if(votes.get(leader).getState() != ServerState.LEADING) predicate = false;
        } else if(logicalclock.get() != electionEpoch) {
            predicate = false;
        }

        return predicate;
    }

    /**
     * This predicate checks that a leader has been elected. It doesn't
     * make a lot of sense without context (check lookForLeader) and it
     * has been separated for testing purposes.
     *
     * @param recv  map of received votes
     * @param ooe   map containing out of election votes (LEADING or FOLLOWING)
     * @param n     Notification
     * @return
     *
     * 该函数用于接收某些状态已经为leading和following的消息时(即部分server认为leader已经选举出来了 ， 自己还在looking状态下),
     * 自己是否可以加入这个集群
     */
    protected boolean ooePredicate(HashMap<Long,Vote> recv,
                                    HashMap<Long,Vote> ooe,
                                    Notification n) {

        return (termPredicate(recv, new Vote(n.version,
                                             n.leader,
                                             n.zxid,
                                             n.electionEpoch,
                                             n.peerEpoch,
                                             n.state))
                && checkLeader(ooe, n.leader, n.electionEpoch));

    }

    synchronized void updateProposal(long leader, long zxid, long epoch){
        if(LOG.isDebugEnabled()){
            LOG.debug("Updating proposal: " + leader + " (newleader), 0x"
                    + Long.toHexString(zxid) + " (newzxid), " + proposedLeader
                    + " (oldleader), 0x" + Long.toHexString(proposedZxid) + " (oldzxid)");
        }

        proposedLeader = leader;    // 服务器当前投给哪台服务器
        proposedZxid = zxid;        // 服务器当前投给哪台服务器的zxid
        proposedEpoch = epoch;      // 服务器当前投给哪台服务器的届号
    }

    synchronized Vote getVote(){
        return new Vote(proposedLeader, proposedZxid, proposedEpoch);
    }

    /**
     * A learning state can be either FOLLOWING or OBSERVING.
     * This method simply decides which one depending on the
     * role of the server.
     *
     * @return ServerState
     */
    private ServerState learningState(){
        if(self.getLearnerType() == LearnerType.PARTICIPANT){
            LOG.debug("I'm a participant: " + self.getId());
            return ServerState.FOLLOWING;
        }
        else{
            LOG.debug("I'm an observer: " + self.getId());
            return ServerState.OBSERVING;
        }
    }

    /**
     * Returns the initial vote value of server identifier.
     *
     * @return long
     */
    private long getInitId(){
        if(self.getLearnerType() == LearnerType.PARTICIPANT)
            return self.getId();
        else return Long.MIN_VALUE;
    }

    /**
     * Returns initial last logged zxid.
     *
     * @return long
     */
    private long getInitLastLoggedZxid(){
        if(self.getLearnerType() == LearnerType.PARTICIPANT)
            return self.getLastLoggedZxid();
        else return Long.MIN_VALUE;
    }

    /**
     * Returns the initial vote value of the peer epoch.
     *
     * @return long
     */
    private long getPeerEpoch(){
        if(self.getLearnerType() == LearnerType.PARTICIPANT)
        	try {
        	    //
        		return self.getCurrentEpoch();
        	} catch(IOException e) {
        		RuntimeException re = new RuntimeException(e.getMessage());
        		re.setStackTrace(e.getStackTrace());
        		throw re;
        	}
        else return Long.MIN_VALUE;
    }

    /**
     * Starts a new round of leader election. Whenever our QuorumPeer
     * changes its state to LOOKING, this method is invoked, and it
     * sends notifications to all other peers.
     */
    public Vote lookForLeader() throws InterruptedException {
        try {
            self.jmxLeaderElectionBean = new LeaderElectionBean();
            MBeanRegistry.getInstance().register(
                    self.jmxLeaderElectionBean, self.jmxLocalPeerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            self.jmxLeaderElectionBean = null;
        }
        if (self.start_fle == 0) {
           self.start_fle = Time.currentElapsedTime();
        }
        try {
            // 1. 投自己
            // 2. 接收其他服务器的选票
            // 3. pk
            // 4. 改票
            // 5. 统计


            HashMap<Long, Vote> recvset = new HashMap<Long, Vote>(); //投票箱，key:其他服务器sid，Vote

            HashMap<Long, Vote> outofelection = new HashMap<Long, Vote>();

            int notTimeout = finalizeWait;

            synchronized(this){
                logicalclock.incrementAndGet(); // 逻辑时钟+1， 服务器在运行的过程中，进行领导者选举的次数
                //更新提议，包含(myid,lastZxid,epoch)，更新为自己
                updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());
            }

            LOG.info("New election. My id =  " + self.getId() +
                    ", proposed zxid=0x" + Long.toHexString(proposedZxid));
            sendNotifications(); // 发送选票，给所有参与投票的服务器发送notifications

            /*
             * Loop in which we exchange notifications until we find a leader
             * 循环交换选票，直到选举出来了一个Leader
             */

            while ((self.getPeerState() == ServerState.LOOKING) &&
                    (!stop)){ //只要没有停止，并且状态处于LOOKING状态
                /*
                 * Remove next notification from queue, times out after 2 times
                 * the termination time
                 * 获取其他服务器的投票
                 */
                Notification n = recvqueue.poll(notTimeout,
                        TimeUnit.MILLISECONDS);

                /*
                 * Sends more notifications if haven't received enough.
                 * Otherwise processes new notification.
                 */
                if(n == null){
                    // 从投票箱里拿不到选票

                    // 获取到的选票是空的，判断有没有需要发送的数据
                    // 因为之前已经sendNotifications过一次了（投票给自己），所以如果走到这里来，发现有东西没发送出去，那么可能的原因就是连接还没建立
                    // 其中某一个服务器的数据已经发送出去了，其他服务器的数据还没有发送，再发一次当前投给了谁
                    if(manager.haveDelivered()){
                        sendNotifications();
                    } else {
                        // 如果所有需要向其他服务器发送的数据都还没有发送，那就去和其他服务器建立连接
                        manager.connectAll();
                    }

                    /*
                     * Exponential backoff
                     */
                    int tmpTimeOut = notTimeout*2;
                    notTimeout = (tmpTimeOut < maxNotificationInterval?
                            tmpTimeOut : maxNotificationInterval);
                    LOG.info("Notification time out: " + notTimeout);
                }
                else if(validVoter(n.sid) && validVoter(n.leader)) {//leader是sid
                    // 如果接收到了结果
                    /*
                     * Only proceed if the vote comes from a replica in the
                     * voting view for a replica in the voting view.
                     */
                    switch (n.state) {
                    case LOOKING:
                        // 发送选票的服务器也在进行领导者选举

                        // If notification > current, replace and send messages out
                        if (n.electionEpoch > logicalclock.get()) { //如果接收到的投票轮次比自己的高
                            logicalclock.set(n.electionEpoch); // 设置自己的时钟为选票的时钟
                            recvset.clear(); // 清空自己的选票

                            // 比较选票对应的服务器和本机，如果选票对应的服务器更新就更新投票为选票所对应的服务器
                            if(totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                    getInitId(), getInitLastLoggedZxid(), getPeerEpoch())) {
                                updateProposal(n.leader, n.zxid, n.peerEpoch);
                            } else {
                                // 否则投给自己
                                updateProposal(getInitId(),
                                        getInitLastLoggedZxid(),
                                        getPeerEpoch());
                            }
                            // 发送投票
                            sendNotifications();
                        } else if (n.electionEpoch < logicalclock.get()) {
                            // 接受到的选票小于自己的时钟，放弃
                            if(LOG.isDebugEnabled()){
                                LOG.debug("Notification election epoch is smaller than logicalclock. n.electionEpoch = 0x"
                                        + Long.toHexString(n.electionEpoch)
                                        + ", logicalclock=0x" + Long.toHexString(logicalclock.get()));
                            }
                            break;
                        } else if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                proposedLeader, proposedZxid, proposedEpoch)) {
                            // 如果时钟相等，同样比较谁的服务器更新
                            updateProposal(n.leader, n.zxid, n.peerEpoch);
                            sendNotifications();
                        }

                        if(LOG.isDebugEnabled()){
                            LOG.debug("Adding vote: from=" + n.sid +
                                    ", proposed leader=" + n.leader +
                                    ", proposed zxid=0x" + Long.toHexString(n.zxid) +
                                    ", proposed election epoch=0x" + Long.toHexString(n.electionEpoch));
                        }
                        // 保存自己接受到的选票
                        recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch));

                        // 根据接受到的选票，以及自己的投票，来判断能不能成为Leader
                        if (termPredicate(recvset,
                                new Vote(proposedLeader, proposedZxid,
                                        logicalclock.get(), proposedEpoch))) {
                            // 如果符合过半验证，本台服务器就认为选出了Leader

                            // Verify if there is any change in the proposed leader
                            while((n = recvqueue.poll(finalizeWait,
                                    TimeUnit.MILLISECONDS)) != null){
                                // 如果又接收到了选票，如果该选票比刚刚选出来的更优秀，则把该选票加入recvqueue中，并且退出这个while，退出这个while会继续走上层的while
                                // 这个地方的逻辑其实就是，我已经选出了一个Leader，但有过来的新的投票，如果说新票所对应的服务器没有我选出来的Leader优秀，那么不用管了，继续读取下一个选票进行同样的判断，
                                // 如果说新的投票所对应的服务器比我选出来的要优秀，那么重新把这个选票放回到recvqueue，并退出当前循环，进入上层循环，重新开始选举
                                // 如果不够优秀，则继续循环，直到没有获取到选票了
                                if(totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                        proposedLeader, proposedZxid, proposedEpoch)){
                                    recvqueue.put(n);
                                    break;
                                }
                            }

                            /*
                             * This predicate is true once we don't read any new
                             * relevant message from the reception queue
                             */
                            if (n == null) {
                                // 如果没有获取到选票了，那么Leader也就选出来了
                                // 如果选出来的是自己，就将自己设置为LEADING, 如果是其他服务器，就将自己设置为对应的状态
                                self.setPeerState((proposedLeader == self.getId()) ?
                                        ServerState.LEADING: learningState());

                                // 将本服务器最终的投票返回
                                Vote endVote = new Vote(proposedLeader,
                                                        proposedZxid,
                                                        logicalclock.get(),
                                                        proposedEpoch);
                                leaveInstance(endVote);
                                return endVote;
                            }
                        }
                        break;
                    case OBSERVING:
                        LOG.debug("Notification from observer: " + n.sid);
                        break;
                    case FOLLOWING:
                    case LEADING:
                        /*
                         * Consider all notifications from the same epoch
                         * together.
                         * 当加入一个服务器的时候接受到FOLLOWING或者LEADING发来的数据
                         */
                        if(n.electionEpoch == logicalclock.get()){
                            recvset.put(n.sid, new Vote(n.leader,
                                                          n.zxid,
                                                          n.electionEpoch,
                                                          n.peerEpoch));

                            if(ooePredicate(recvset, outofelection, n)) {
                                self.setPeerState((n.leader == self.getId()) ?
                                        ServerState.LEADING: learningState());

                                Vote endVote = new Vote(n.leader,
                                        n.zxid,
                                        n.electionEpoch,
                                        n.peerEpoch);
                                leaveInstance(endVote);
                                return endVote;
                            }
                        }

                        /*
                         * Before joining an established ensemble, verify
                         * a majority is following the same leader.
                         */
                        outofelection.put(n.sid, new Vote(n.version,
                                                            n.leader,
                                                            n.zxid,
                                                            n.electionEpoch,
                                                            n.peerEpoch,
                                                            n.state));

                        if(ooePredicate(outofelection, outofelection, n)) {
                            synchronized(this){
                                logicalclock.set(n.electionEpoch);
                                self.setPeerState((n.leader == self.getId()) ?
                                        ServerState.LEADING: learningState());
                            }
                            Vote endVote = new Vote(n.leader,
                                                    n.zxid,
                                                    n.electionEpoch,
                                                    n.peerEpoch);
                            leaveInstance(endVote);
                            return endVote;
                        }
                        break;
                    default:
                        LOG.warn("Notification state unrecognized: {} (n.state), {} (n.sid)",
                                n.state, n.sid);
                        break;
                    }
                } else {
                    if (!validVoter(n.leader)) {
                        LOG.warn("Ignoring notification for non-cluster member sid {} from sid {}", n.leader, n.sid);
                    }
                    if (!validVoter(n.sid)) {
                        LOG.warn("Ignoring notification for sid {} from non-quorum member sid {}", n.leader, n.sid);
                    }
                }
            }
            return null;
        } finally {
            try {
                if(self.jmxLeaderElectionBean != null){
                    MBeanRegistry.getInstance().unregister(
                            self.jmxLeaderElectionBean);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister with JMX", e);
            }
            self.jmxLeaderElectionBean = null;
            LOG.debug("Number of connection processing threads: {}",
                    manager.getConnectionThreadCount());
        }
    }

    /**
     * Check if a given sid is represented in either the current or
     * the next voting view
     *
     * @param sid     Server identifier
     * @return boolean
     */
    private boolean validVoter(long sid) {
        return self.getVotingView().containsKey(sid);
    }
}
