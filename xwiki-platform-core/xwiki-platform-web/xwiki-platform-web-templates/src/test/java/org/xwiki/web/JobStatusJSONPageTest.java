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
package org.xwiki.web;

import java.io.StringWriter;
import java.util.Collection;

import javax.inject.Inject;
import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.icon.IconManagerScriptService;
import org.xwiki.job.DefaultJobStatus;
import org.xwiki.job.DefaultRequest;
import org.xwiki.job.JobException;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.localization.script.LocalizationScriptService;
import org.xwiki.script.service.ScriptService;
import org.xwiki.template.TemplateManager;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.page.PageTest;
import org.xwiki.velocity.VelocityManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToCompressingWhiteSpace;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Page test for {@code job_status_json.vm} and the {@code job_macros.vm} macros it uses.
 *
 * @version $Id$
 */
class JobStatusJSONPageTest extends PageTest
{
    @Inject
    private TemplateManager templateManager;

    @MockComponent(classToMock = IconManagerScriptService.class)
    @Named("icon")
    private ScriptService iconManagerScriptService;

    @BeforeEach
    void setup() throws Exception
    {
        when(((IconManagerScriptService)this.iconManagerScriptService).renderHTML(any(String.class)))
            .then(invocationOnMock -> invocationOnMock.getArgument(0) + "Icon");

        // Render a list of translation keys (tried in order until one exists) as the list itself, so that the tests
        // can verify the whole fallback chain.
        LocalizationScriptService localizationScriptService =
            this.componentManager.getInstance(ScriptService.class, "localization");
        when(localizationScriptService.render(anyCollection()))
            .then(invocationOnMock -> String.valueOf(invocationOnMock.<Collection<String>>getArgument(0)));
    }

    @Test
    void nonExistingJob() throws Exception
    {
        this.stubRequest.put("jobId", "<test>");
        this.stubRequest.put("translationPrefix", "<test>");

        String output = this.templateManager.render("job_status_json.vm");

        assertThat(output.strip(), equalToCompressingWhiteSpace("""
            <div class="box errormessage ">
            <span class="icon-block">exclamationIcon</span>
            <span class="sr-only">error</span>
            <div>
                &#60;test&#62;.notFound
            </div>
            </div>"""));
    }

    @Test
    void displayJobStatusOfFailedJob() throws Exception
    {
        DefaultJobStatus<DefaultRequest> status =
            createFinishedJobStatus(new JobException("Failed to move the page", new RuntimeException("cause")));

        String output = renderJobMacro(status, "#displayJobStatus($jobStatus 'test')");

        // The message is computed from the displayed job status.
        assertThat(output, containsString("box errormessage"));
        assertThat(output, containsString("job.status.testJob.error, job.status.error]"));
        assertThat(output, not(containsString("job.status.success")));
    }

    private DefaultJobStatus<DefaultRequest> createFinishedJobStatus(Throwable error)
    {
        DefaultRequest request = new DefaultRequest();
        request.setId("job");
        DefaultJobStatus<DefaultRequest> status = new DefaultJobStatus<>("testJob", request, null, null, null);
        status.setError(error);
        status.setState(JobStatus.State.FINISHED);
        return status;
    }

    private String renderJobMacro(JobStatus status, String macroCall) throws Exception
    {
        VelocityManager velocityManager = this.componentManager.getInstance(VelocityManager.class);
        velocityManager.getVelocityContext().put("jobStatus", status);

        StringWriter writer = new StringWriter();
        this.templateManager.render(this.templateManager.createStringTemplate("jobMacrosTest",
            "#template('job_macros.vm')\n" + macroCall, null, null), writer);
        return writer.toString();
    }
}
