/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.coordinator.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TimerTask;

import org.apache.felix.service.coordinator.Coordination;
import org.apache.felix.service.coordinator.CoordinationException;
import org.apache.felix.service.coordinator.Participant;

@SuppressWarnings("deprecation")
public class CoordinationImpl implements Coordination
{

    /** Active */
    private static final int ACTIVE = 1;

    /** Coordination termination started */
    private static final int TERMINATING = 2;

    /** Coordination completed */
    private static final int TERMINATED = 3;

    private final CoordinatorImpl owner;

    private final long id;

    private final String name;

    // TODO: timeout must be enforced
    private long deadLine;

    /**
     * Access to this field must be synchronized as long as the expected state
     * is {@link #ACTIVE}. Once the state has changed, further updates to this
     * instance will not take place any more and the state will only be modified
     * by the thread successfully setting the state to {@link #TERMINATING}.
     */
    private volatile int state;

    private Throwable failReason;

    private ArrayList<Participant> participants;

    private HashMap<Class<?>, Object> variables;

    private TimerTask timeoutTask;

    private Thread initiatorThread;

    public CoordinationImpl(final CoordinatorImpl owner, final long id, final String name, final int timeOutInMs)
    {
        // TODO: validate name against Bundle Symbolic Name pattern

        this.owner = owner;
        this.id = id;
        this.name = name;
        this.state = ACTIVE;
        this.participants = new ArrayList<Participant>();
        this.variables = new HashMap<Class<?>, Object>();
        this.deadLine = (timeOutInMs > 0) ? System.currentTimeMillis() + timeOutInMs : 0;
        this.initiatorThread = Thread.currentThread();

        scheduleTimeout(deadLine);
    }

    public long getId()
    {
        return this.id;
    }

    public String getName()
    {
        return name;
    }

    public boolean fail(Throwable reason)
    {
        if (startTermination())
        {
            this.failReason = reason;

            // consider failure reason (if not null)
            for (Participant part : participants)
            {
                try
                {
                    part.failed(this);
                }
                catch (Exception e)
                {
                    // TODO: log
                }

                // release the participant for other coordinations
                owner.releaseParticipant(part);
            }

            state = TERMINATED;

            synchronized (this)
            {
                this.notifyAll();
            }

            return true;
        }
        return false;
    }

    public void end()
    {
        if (startTermination())
        {
            boolean partialFailure = false;
            for (Participant part : participants)
            {
                try
                {
                    part.ended(this);
                }
                catch (Exception e)
                {
                    // TODO: log
                    partialFailure = true;
                }

                // release the participant for other coordinations
                owner.releaseParticipant(part);
            }

            state = TERMINATED;

            synchronized (this)
            {
                this.notifyAll();
            }

            if (partialFailure)
            {
                throw new CoordinationException("One or more participants threw while ending the coordination", this,
                    CoordinationException.PARTIALLY_ENDED);
            }
        }
        else
        {
            // already terminated
            throw new CoordinationException("Coordination " + id + "/" + name + " has already terminated", this,
                CoordinationException.ALREADY_ENDED);
        }
    }


    public Collection<Participant> getParticipants()
    {
        // synchronize access to the state to prevent it from being changed
        // while we create a copy of the participant list
        synchronized (this)
        {
            if (state == ACTIVE)
            {
                return new ArrayList<Participant>(participants);
            }
        }

        return Collections.<Participant> emptyList();
    }

    public Throwable getFailure()
    {
        return failReason;
    }


    /**
     * Adds the participant to the end of the list of participants of this
     * coordination.
     * <p>
     * This method blocks if the given participant is currently participating in
     * another coordination.
     * <p>
     * Participants can only be added to a coordination if it is active.
     *
     * @throws org.apache.felix.service.coordination.CoordinationException if
     *             the participant cannot currently participate in this
     *             coordination
     */
    public void addParticipant(Participant p)
    {

        // ensure participant only pariticipates on a single coordination
        // this blocks until the participant can participate or until
        // a timeout occurrs (or a deadlock is detected)
        owner.lockParticipant(p, this);

        // synchronize access to the state to prevent it from being changed
        // while adding the participant
        synchronized (this)
        {
            if (isTerminated())
            {
                owner.releaseParticipant(p);

                throw new CoordinationException("Cannot add Participant " + p + " to terminated Coordination", this,
                    (getFailure() != null) ? CoordinationException.FAILED : CoordinationException.ALREADY_ENDED);
            }

            if (!participants.contains(p))
            {
                participants.add(p);
            }
        }
    }

    public Map<Class<?>, ?> getVariables()
    {
        return variables;
    }

    public long extendTimeout(long timeOutInMs)
    {
        synchronized (this)
        {
            if (isTerminated())
            {
                throw new CoordinationException("Cannot extend timeout on terminated Coordination", this,
                    (getFailure() != null) ? CoordinationException.FAILED : CoordinationException.ALREADY_ENDED);
            }

            if (timeOutInMs > 0)
            {
                this.deadLine += timeOutInMs;
                scheduleTimeout(this.deadLine);
            }

            return this.deadLine;
        }
    }

    /**
     * Returns whether the coordination has ended.
     * <p>
     * The return value of <code>false</code> may be a transient situation if
     * the coordination is in the process of terminating.
     */
    public boolean isTerminated()
    {
        return state != ACTIVE;
    }

    public Thread getThread()
    {
        return initiatorThread;
    }

    public void join(long timeoutInMillis) throws InterruptedException
    {
        synchronized (this)
        {
            if (!isTerminated())
            {
                this.wait(timeoutInMillis);
            }
        }
    }

    public Coordination push()
    {
        // TODO: Check whether this has already been pushed !
        // throw new CoordinationException("Coordination already pushed", this, CoordinationException.ALREADY_PUSHED);

        return owner.push(this);
    }

    //-------

    /**
     * Initiates a coordination timeout. Called from the timer task scheduled by
     * the {@link #scheduleTimeout(long)} method.
     * <p>
     * This method is inteded to only be called from the scheduled timer task.
     */
    void timeout()
    {
        // Fail the Coordination upon timeout
        fail(TIMEOUT);
    }

    long getDeadLine()
    {
        return this.deadLine;
    }

    /**
     * If this coordination is still active, this method initiates the
     * termination of the coordination by setting the state to
     * {@value State#TERMINATING}, unregistering from the
     * {@link CoordinationMgr} and ensuring there is no timeout task active any
     * longer to timeout this coordination.
     *
     * @return <code>true</code> If the coordination was active and termination
     *         can continue. If <code>false</code> is returned, the coordination
     *         must be considered terminated (or terminating) in the current
     *         thread and no further termination processing must take place.
     */
    private synchronized boolean startTermination()
    {
        if (state == ACTIVE)
        {
            state = TERMINATING;
            owner.unregister(this);
            scheduleTimeout(-1);
            return true;
        }

        // this coordination is not active any longer, nothing to do
        return false;
    }

    /**
     * Helper method for timeout scheduling. If a timer is currently scheduled
     * it is canceled. If the new timeout value is a positive value a new timer
     * is scheduled to fire at the desired time (in the future)
     *
     * @param deadline The at which to schedule the timer
     */
    private void scheduleTimeout(final long deadLine)
    {
        if (timeoutTask != null)
        {
            owner.schedule(timeoutTask, -1);
            timeoutTask = null;
        }

        if (deadLine > System.currentTimeMillis())
        {
            timeoutTask = new TimerTask()
            {
                @Override
                public void run()
                {
                    CoordinationImpl.this.timeout();
                }
            };

            owner.schedule(timeoutTask, deadLine);
        }
    }
}
