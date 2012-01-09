/*
 * Copyright 2011 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vanilla.java.affinity;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author peter.lawrey
 */
public class AffinityLock {
    // TODO It seems like on virtualized platforms .availableProcessors() value can change at
    // TODO runtime. We should think about how to adopt to such change
    public static final String AFFINITY_RESERVED = "affinity.reserved";

    public static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    public static final long BASE_AFFINITY = AffinitySupport.getAffinity();
    public static final long RESERVED_AFFINITY = getReservedAffinity0();

    private static final Logger LOGGER = Logger.getLogger(AffinityLock.class.getName());

    private static final AffinityLock[] LOCKS = new AffinityLock[PROCESSORS];
    private static final AffinityLock NONE = new AffinityLock(-1, false, false);

    private final int id;
    private final boolean base;
    private final boolean reserved;
    Thread assignedThread;

    AffinityLock(int id, boolean base, boolean reserved) {
        this.id = id;
        this.base = base;
        this.reserved = reserved;
    }

    static {
        for (int i = 0; i < PROCESSORS; i++)
            LOCKS[i] = new AffinityLock(i, ((BASE_AFFINITY >> i) & 1) != 0, ((RESERVED_AFFINITY >> i) & 1) != 0);
    }

    private static long getReservedAffinity0() {
        String reservedAffinity = System.getProperty(AFFINITY_RESERVED);
        if (reservedAffinity == null || reservedAffinity.trim().isEmpty())
            return ((1 << PROCESSORS) - 1) ^ BASE_AFFINITY;
        return Long.parseLong(reservedAffinity, 16);
    }

    public static AffinityLock acquireLock() {
        synchronized (AffinityLock.class) {
            for (int i = PROCESSORS - 1; i > 0; i--) {
                AffinityLock al = LOCKS[i];
                if (!al.reserved) continue;
                if (al.assignedThread != null) {
                    if (al.assignedThread.isAlive()) continue;
                    LOGGER.severe("Lock assigned to " + al.assignedThread + " but this thread is dead.");
                }
                al.assignCurrentThread();
                return al;
            }
        }
        if (LOGGER.isLoggable(Level.WARNING))
            LOGGER.warning("No reservable CPU for " + Thread.currentThread());
        return AffinityLock.NONE;
    }

    private void assignCurrentThread() {
        assignedThread = Thread.currentThread();
        AffinitySupport.setAffinity(1L << id);
        if (LOGGER.isLoggable(Level.INFO))
            LOGGER.info("Assigning cpu " + id + " to " + assignedThread);
    }

    public void release() {
        if (this == NONE) return;

        Thread t = Thread.currentThread();

        synchronized (AffinityLock.class) {
            if (assignedThread != t)
                throw new IllegalStateException("Cannot release lock " + id + " assigned to " + assignedThread);
            if (LOGGER.isLoggable(Level.INFO))
                LOGGER.info("Releasing cpu " + id + " from " + t);
            assignedThread = null;
        }
        AffinitySupport.setAffinity(BASE_AFFINITY);
    }

    public static String dumpLocks() {
        return dumpLocks0(LOCKS);
    }

    static String dumpLocks0(AffinityLock[] locks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < locks.length; i++) {
            AffinityLock al = locks[i];
            sb.append(i).append(": ");
            if (al.assignedThread != null)
                sb.append(al.assignedThread).append(" alive=").append(al.assignedThread.isAlive());
            else if (al.reserved)
                sb.append("Reserved for this application");
            else if (al.base)
                sb.append("General use CPU");
            else
                sb.append("CPU not available");
            sb.append('\n');
        }
        return sb.toString();
    }
}
