/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.mail.internal.thread;

import java.util.concurrent.CountDownLatch;

import jakarta.inject.Named;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link MailSenderInitializerListener}.
 *
 * @version $Id$
 */
@ComponentTest
class MailSenderInitializerListenerTest
{
    @InjectMockComponents
    private MailSenderInitializerListener listener;

    @MockComponent
    @Named("prepare")
    private MailRunnable prepareMailRunnable;

    @MockComponent
    @Named("send")
    private MailRunnable sendMailRunnable;

    /**
     * Make the passed runnable block until its thread is interrupted (like the real mail runnables do when sleeping).
     */
    private void blockUntilInterrupted(MailRunnable runnable)
    {
        doAnswer(invocation -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return null;
        }).when(runnable).run();
    }

    private Thread getThread(String fieldName) throws Exception
    {
        return (Thread) FieldUtils.readField(this.listener, fieldName, true);
    }

    @Test
    void disposeStopsTheMailThreads() throws Exception
    {
        blockUntilInterrupted(this.prepareMailRunnable);
        blockUntilInterrupted(this.sendMailRunnable);

        this.listener.onEvent(new ApplicationReadyEvent(), null, null);

        Thread prepareThread = getThread("prepareMailThread");
        Thread sendThread = getThread("sendMailThread");
        assertNotNull(prepareThread);
        assertNotNull(sendThread);
        assertTrue(prepareThread.isAlive());
        assertTrue(sendThread.isAlive());

        this.listener.dispose();

        verify(this.sendMailRunnable).stopProcessing();
        verify(this.prepareMailRunnable).stopProcessing();
        assertFalse(sendThread.isAlive());
        assertFalse(prepareThread.isAlive());
    }

    @Test
    void disposeWhenNotStarted() throws Exception
    {
        this.listener.dispose();

        verify(this.sendMailRunnable, never()).stopProcessing();
        verify(this.prepareMailRunnable, never()).stopProcessing();
    }
}
