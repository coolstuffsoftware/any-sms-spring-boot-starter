/*
   Copyright (C) 2017 by the original Authors.

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
*/
package software.coolstuff.springframework.anysms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import org.apache.commons.lang3.StringUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.SpringBootDependencyInjectionTestExecutionListener;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import software.coolstuff.springframework.anysms.config.TestConfiguration;
import software.coolstuff.springframework.anysms.exception.SmsWrongConfigurationException;
import software.coolstuff.springframework.anysms.model.SmsGateway;
import software.coolstuff.springframework.anysms.model.SmsStatus;
import software.coolstuff.springframework.anysms.service.api.SmsService;

/**
 * @author mufasa1976
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    classes = {
        TestConfiguration.class,
        SmsAutoConfiguration.class
    })
@TestExecutionListeners({
    SpringBootDependencyInjectionTestExecutionListener.class,
    DependencyInjectionTestExecutionListener.class
})
@RestClientTest(SmsService.class)
@ActiveProfiles("WITH-DEFAULT")
public class SmsTest {

  private final static String REST_URL = "https://www.any-sms.biz/gateway/send_sms.php";
  private final static String VELOCITY_PATH_PREFIX = "/velocity/";

  @Autowired
  private SmsService smsService;

  @Autowired
  private SmsServiceProperties properties;

  @Autowired
  private VelocityEngine velocityEngine;

  private MockRestServiceServer server;

  @Before
  public void setUp() {
    server = MockRestServiceServer.createServer(((SmsServiceImpl) smsService).getRestTemplate());
  }

  @Test
  public void testOK() throws Exception {
    SmsStatus expectedStatus = SmsStatus.builder()
        .phoneNumber("00436641234567")
        .messageId(12345678L)
        .costs(6.5F)
        .balance(42.16F)
        .creditLimit(2.0F)
        .build();
    String message = "Test message";

    preparePositiveFeedback(expectedStatus, properties.getDefaultGateway(), message);

    SmsStatus status = smsService.sendSms("+436641234567", message);
    server.verify();

    assertThat(status).isNotNull();
    assertThat(status).isEqualTo(expectedStatus);
  }

  private void preparePositiveFeedback(SmsStatus expectedStatus, SmsGateway gateway, String message) throws IOException {
    String phoneNumber = expectedStatus.getPhoneNumber();
    if (StringUtils.startsWith(phoneNumber, "00")) {
      phoneNumber = "+" + StringUtils.substring(phoneNumber, 2);
    }

    Context context = new VelocityContext();
    context.put("nummer", expectedStatus.getPhoneNumber());
    context.put("msgid", expectedStatus.getMessageId());
    context.put("preis", expectedStatus.getCosts());
    context.put("guthaben", expectedStatus.getBalance());
    context.put("limit", expectedStatus.getCreditLimit());

    MultiValueMap<String, String> data = preparePostData(gateway, phoneNumber, message);
    server
        .expect(requestTo(REST_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().formData(data))
        .andRespond(withSuccess(merge("sendResponseOK.vm", context), MediaType.TEXT_HTML));
  }

  private MultiValueMap<String, String> preparePostData(SmsGateway gateway, String phoneNumber, String message) {
    MultiValueMap<String, String> data = new LinkedMultiValueMap<>();
    data.add("id", properties.getUsername());
    data.add("pass", properties.getPassword());
    data.add("absender", properties.getSender());
    data.add("xml", "1");
    if (properties.isTest()) {
      data.add("test", "1");
    }
    data.add("gateway", Integer.toString(gateway.getGateway()));
    data.add("nummer", phoneNumber);
    data.add("text", message);
    return data;
  }

  private String merge(String templateName, Context context) throws IOException {
    String prefixedTemplateName = templateName;
    if (!StringUtils.startsWith(templateName, VELOCITY_PATH_PREFIX)) {
      prefixedTemplateName = VELOCITY_PATH_PREFIX + templateName;
      if (StringUtils.startsWith(templateName, "/")) {
        prefixedTemplateName = VELOCITY_PATH_PREFIX + StringUtils.substring(templateName, 1);
      }
    }
    Template template = velocityEngine.getTemplate(prefixedTemplateName);
    try (Writer writer = new StringWriter()) {
      template.merge(context, writer);
      writer.flush();
      return writer.toString();
    }
  }

  @Test(expected = SmsWrongConfigurationException.class)
  public void testNOK_WrongCredentials() throws Exception {
    String phoneNumber = "+436641234567";
    String message = "Test Message";

    prepareNegativeFeedback(SmsServiceImpl.Error.WRONG_USERNAME_OR_PASSWORD, 42.0F, properties.getDefaultGateway(), phoneNumber, message);

    smsService.sendSms(phoneNumber, message);
  }

  private void prepareNegativeFeedback(
      SmsServiceImpl.Error expectedErrorCode,
      float expectedBalance,
      SmsGateway gateway,
      String phoneNumber,
      String message)
      throws IOException {
    String convertedPhoneNumber = phoneNumber;
    if (StringUtils.startsWith(convertedPhoneNumber, "+")) {
      convertedPhoneNumber = "00" + StringUtils.substring(phoneNumber, 1);
    }
    Context context = new VelocityContext();
    context.put("error", expectedErrorCode.getErrorCode());
    context.put("nummer", convertedPhoneNumber);
    context.put("guthaben", expectedBalance);

    MultiValueMap<String, String> data = preparePostData(gateway, phoneNumber, message);
    server
        .expect(requestTo(REST_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().formData(data))
        .andRespond(withSuccess(merge("sendResponseNOK.vm", context), MediaType.TEXT_HTML));

  }
}
