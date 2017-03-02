/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.kafka.common.requests.InitPidResponse.INVALID_PID;

/**
 * A class which maintains state for transactions. Also keeps the state necessary to ensure idempotent production.
 */
public class TransactionState {
    private volatile long pid;
    private volatile short epoch;
    private final boolean idempotenceEnabled;
    private final Map<TopicPartition, Integer> sequenceNumbers;
    private final Lock pidLock;
    private final Condition hasPidCondition;

    public static class PidAndEpoch {
        public long pid;
        public short epoch;

        PidAndEpoch(long pid, short epoch) {
            this.pid = pid;
            this.epoch = epoch;
        }

        public boolean isValid() {
            return pid != INVALID_PID;
        }
    }

    public TransactionState(boolean idempotenceEnabled) {
        pid = INVALID_PID;
        epoch = 0;
        sequenceNumbers = new HashMap<>();
        this.idempotenceEnabled = idempotenceEnabled;

        this.pidLock = new ReentrantLock();
        this.hasPidCondition = pidLock.newCondition();
    }

    public boolean hasPid() {
        return pid != INVALID_PID;
    }

    /**
     * A blocking call to get the pid and epoch for the producer. If the PID and epoch has not been set, this method
     * will block for at most maxWaitTimeMs. It is expected that this method be called from application thread
     * contexts (ie. through Producer.send). The PID it self will be retrieved in the background thread.
     * @param maxWaitTimeMs The maximum time to block.
     * @return a PidAndEpoch object. Callers must call the 'isValid' method fo the returned object to ensure that a
     *         valid Pid and epoch is actually returned.
     */
    public PidAndEpoch pidAndEpoch(long maxWaitTimeMs) throws InterruptedException {
        pidLock.lock();
        try {
            while (!hasPid()) {
                hasPidCondition.await(maxWaitTimeMs, TimeUnit.MILLISECONDS);
            }
        } finally {
            pidLock.unlock();
        }
        return new PidAndEpoch(pid, epoch);
    }


    /**
     * Set the pid and epoch atomically. This method will signal any callers blocked on the `pidAndEpoch` method
     * once the pid is set. This method will be called on the background thread when the broker responds with the pid.
     */
    public void setPidAndEpoch(long pid, short epoch) {
        pidLock.lock();
        try {
            this.pid = pid;
            this.epoch = epoch;
            if (this.pid != INVALID_PID)
                hasPidCondition.signalAll();
        } finally {
            pidLock.unlock();
        }
    }

    public long pid() {
        return pid;
    }

    public short epoch() {
        return epoch;
    }

    /**
     * This method is used when the producer needs to reset it's internal state because of an irrecoverable exception
     * from the broker.
    */
    public synchronized void reset() {
        setPidAndEpoch(INVALID_PID, (short) 0);
        this.sequenceNumbers.clear();
    }

    /**
     * Returns the next sequence number to be written to the given TopicPartition.
     */
    public synchronized Integer sequenceNumber(TopicPartition topicPartition) {
        if (!idempotenceEnabled) {
            throw new IllegalStateException("Attempting to access sequence numbers when idempotence is disabled");
        }
        if (!sequenceNumbers.containsKey(topicPartition)) {
            sequenceNumbers.put(topicPartition, 0);
        }
        return sequenceNumbers.get(topicPartition);
    }


    public synchronized void incrementSequenceNumber(TopicPartition topicPartition, int increment) {
        if (!idempotenceEnabled) {
            throw new IllegalStateException("Attempt to modify sequence numbers when idempotence is disabled");
        }
        if (!sequenceNumbers.containsKey(topicPartition)) {
            sequenceNumbers.put(topicPartition, 0);
        }
        int currentSequenceNumber = sequenceNumbers.get(topicPartition);
        currentSequenceNumber += increment;
        sequenceNumbers.put(topicPartition, currentSequenceNumber);
    }
}
