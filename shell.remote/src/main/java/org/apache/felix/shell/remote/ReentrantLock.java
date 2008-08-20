/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.felix.shell.remote;


/**
 * Implements a reentrant lock.
 * <p/>
 * Public domain code.
 */
class ReentrantLock
{

    protected Thread m_Owner = null;
    protected long m_Holds = 0;


    public void acquire() throws InterruptedException
    {
        //log.debug("acquire()::" + Thread.currentThread().toString());
        if ( Thread.interrupted() )
            throw new InterruptedException();
        Thread caller = Thread.currentThread();
        synchronized ( this )
        {
            if ( caller == m_Owner )
                ++m_Holds;
            else
            {
                try
                {
                    while ( m_Owner != null )
                        wait();
                    m_Owner = caller;
                    m_Holds = 1;
                }
                catch ( InterruptedException ex )
                {
                    notify();
                    throw ex;
                }
            }
        }
    }//acquire


    public boolean attempt( long msecs ) throws InterruptedException
    {
        //log.debug("attempt()::" + Thread.currentThread().toString());
        if ( Thread.interrupted() )
            throw new InterruptedException();
        Thread caller = Thread.currentThread();
        synchronized ( this )
        {
            if ( caller == m_Owner )
            {
                ++m_Holds;
                return true;
            }
            else if ( m_Owner == null )
            {
                m_Owner = caller;
                m_Holds = 1;
                return true;
            }
            else if ( msecs <= 0 )
                return false;
            else
            {
                long waitTime = msecs;
                long start = System.currentTimeMillis();
                try
                {
                    for ( ;; )
                    {
                        wait( waitTime );
                        if ( caller == m_Owner )
                        {
                            ++m_Holds;
                            return true;
                        }
                        else if ( m_Owner == null )
                        {
                            m_Owner = caller;
                            m_Holds = 1;
                            return true;
                        }
                        else
                        {
                            waitTime = msecs - ( System.currentTimeMillis() - start );
                            if ( waitTime <= 0 )
                                return false;
                        }
                    }
                }
                catch ( InterruptedException ex )
                {
                    notify();
                    throw ex;
                }
            }
        }
    }//attempt


    /**
     * Release the lock.
     *
     * @throws Error thrown if not current owner of lock
     */
    public synchronized void release()
    {
        //log.debug("release()::" + Thread.currentThread().toString());
        if ( Thread.currentThread() != m_Owner )
            throw new Error( "Illegal Lock usage" );

        if ( --m_Holds == 0 )
        {
            m_Owner = null;
            notify();
        }
    }//release


    /**
     * Release the lock N times. <code>release(n)</code> is
     * equivalent in effect to:
     * <pre>
     *   for (int i = 0; i < n; ++i) release();
     * </pre>
     * <p/>
     *
     * @param n times the lock should be released.
     * @throws Error thrown if not current owner of lock
     *               or has fewer than N holds on the lock
     */
    public synchronized void release( long n )
    {
        if ( Thread.currentThread() != m_Owner || n > m_Holds )
            throw new Error( "Illegal Lock usage" );

        m_Holds -= n;
        if ( m_Holds == 0 )
        {
            m_Owner = null;
            notify();
        }
    }//release


    /**
     * Return the number of unreleased acquires performed
     * by the current thread.
     * Returns zero if current thread does not hold lock.
     *
     * @return the number of unreleased acquires performed by the owner thread.
     */
    public synchronized long holds()
    {
        if ( Thread.currentThread() != m_Owner )
            return 0;
        return m_Holds;
    }//holds

}//class ReentrantLock
