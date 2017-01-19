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

import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Optional;

import javax.annotation.PostConstruct;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import com.google.common.collect.Lists;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.coolstuff.springframework.anysms.exception.SmsBillingException;
import software.coolstuff.springframework.anysms.exception.SmsException;
import software.coolstuff.springframework.anysms.exception.SmsNotSentException;
import software.coolstuff.springframework.anysms.exception.SmsSpamException;
import software.coolstuff.springframework.anysms.exception.SmsWrongConfigurationException;
import software.coolstuff.springframework.anysms.model.SmsGateway;
import software.coolstuff.springframework.anysms.model.SmsStatus;
import software.coolstuff.springframework.anysms.service.api.SmsService;

/**
 * @author mufasa1976
 */
@Slf4j
class SmsServiceImpl implements SmsService {

  private final static String ROOT_URI = "https://www.any-sms.biz/gateway";

  @Getter
  private final RestTemplate restTemplate;
  private final MessageSourceAccessor messageSourceAccessor = SmsServiceMessageSource.getAccessor();

  @Autowired
  private SmsServiceProperties properties;

  SmsServiceImpl(
      RestTemplateBuilder restTemplateBuilder,
      MappingJackson2XmlHttpMessageConverter mappingJackson2XmlHttpMessageConverter) {
    ObjectMapper objectMapper = mappingJackson2XmlHttpMessageConverter.getObjectMapper();
    objectMapper.registerModule(new JaxbAnnotationModule());
    MappingJackson2XmlHttpMessageConverter customizedXmlHttpMessConverter = new MappingJackson2XmlHttpMessageConverter(mappingJackson2XmlHttpMessageConverter.getObjectMapper());
    customizedXmlHttpMessConverter.setDefaultCharset(Charset.forName("ISO-8859-15"));
    customizedXmlHttpMessConverter.setSupportedMediaTypes(Lists.newArrayList(MediaType.APPLICATION_XML, MediaType.TEXT_HTML));
    restTemplate = restTemplateBuilder
        .messageConverters(customizedXmlHttpMessConverter)
        .additionalMessageConverters(new FormHttpMessageConverter())
        .rootUri(ROOT_URI)
        .build();
  }

  @PostConstruct
  protected void afterPropertiesSet() throws Exception {
    Validate.notBlank(properties.getSender());
    if (isNoPhoneNumber(properties.getSender())) {
      if (properties.getSender().length() > 11) {
        throw new IllegalStateException("Sender must have no more than 11 Characters");
      }
    } else if (properties.getSender().length() > 16) {
      throw new IllegalStateException("Sender Phone Number must not have more than 16 Characters");
    }
  }

  @Override
  public SmsStatus sendSms(String phoneNumber, String message) throws SmsException {
    if (properties.getDefaultGateway() == null) {
      throw new IllegalStateException("No Default Gateway defined");
    }
    return sendSms(properties.getDefaultGateway(), phoneNumber, message);
  }

  @Override
  public SmsStatus sendSms(SmsGateway gateway, String phoneNumber, String message) throws SmsException {
    Validate.notNull(gateway);
    checkPhoneNumber(phoneNumber);

    LinkedMultiValueMap<String, String> data = new LinkedMultiValueMap<>();
    data.add("id", properties.getUsername());
    data.add("pass", properties.getPassword());
    data.add("gateway", Integer.toString(gateway.getGateway()));
    data.add("absender", properties.getSender());
    data.add("nummer", phoneNumber);
    if (StringUtils.isNotBlank(message)) {
      data.add("text", message);
    }
    data.add("xml", "1");
    if (properties.isTest()) {
      data.add("test", "1");
    }

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    ResponseEntity<Status> response = restTemplate.exchange("/send_sms.php", HttpMethod.POST, new HttpEntity<>(data, headers), Status.class);

    Status status = response.getBody();
    switch (status.getError()) {
      case WRONG_USERNAME_OR_PASSWORD:
      case INSUFFICIENT_FUNDS_MAIN_ACCOUNT:
      case INSUFFICIENT_FUNDS_SUB_ACCOUNT:
        status.getError().performAction(messageSourceAccessor, properties.getUsername());
        break;
      case WRONG_IP:
        try {
          status.getError().performAction(messageSourceAccessor, InetAddress.getLocalHost().getHostAddress());
          break;
        } catch (UnknownHostException e) {
          log.error("Error while getting the IP of the Localhost");
          throw new RuntimeException("Error while getting the IP of the Localhost", e);
        }
      case WRONG_GATEWAY:
        status.getError().performAction(messageSourceAccessor, gateway);
        break;
      case SMS_NOT_SENT:
      case SPAM:
        status.getError().performAction(messageSourceAccessor, phoneNumber);
        break;
      default:
        status.getError().performAction(messageSourceAccessor);
    }
    return status.convert();
  }

  private void checkPhoneNumber(String phoneNumber) {
    Validate.notNull(phoneNumber);
    if (isNoPhoneNumber(phoneNumber)) {
      throw new IllegalArgumentException(String.format("%s is not a valid Phone Number", phoneNumber));
    }
  }

  private boolean isNoPhoneNumber(String phoneNumber) {
    return !phoneNumber.matches("^\\+?\\d{10,25}$");
  }

  @Data
  private static class Status {
    @XmlJavaTypeAdapter(ErrorXmlAdapter.class)
    private Error error;
    private String nummer;
    private Long msgid;
    private Float preis;
    private Float guthaben;
    private Float limit;

    public SmsStatus convert() {
      return SmsStatus.builder()
          .phoneNumber(getNummer())
          .messageId(getMsgid())
          .costs(getPreis())
          .balance(getGuthaben())
          .creditLimit(getLimit())
          .build();
    }
  }

  @Getter
  @RequiredArgsConstructor
  public static enum Error {
    OK(0),
    WRONG_USERNAME_OR_PASSWORD(-1, SmsWrongConfigurationException.class, "SmsException.wrongCredentials"),
    WRONG_IP(-2, SmsWrongConfigurationException.class, "SmsException.wrongIp"),
    INSUFFICIENT_FUNDS_MAIN_ACCOUNT(-3, SmsBillingException.class, "SmsException.insufficientFundsOnMainAccount"),
    INSUFFICIENT_FUNDS_SUB_ACCOUNT(-4, SmsBillingException.class, "SmsException.insufficientFundsOnSubAccount"),
    SMS_NOT_SENT(-5, SmsNotSentException.class, "SmsException.smsNotSent"),
    WRONG_GATEWAY(-6, SmsWrongConfigurationException.class, "SmsException.wrongGateway"),
    SPAM(-9, SmsSpamException.class, "SmsException.spam"),
    NO_BILLING_INFO(-18, SmsBillingException.class, "SmsException.noBillingInformation");

    private final static String DEFAULT_MESSAGE_KEY = "SmsException.unknownError";

    private final int errorCode;
    private Class<? extends SmsException> smsExceptionClass;
    private String messageKey;

    private Error(final int errorCode, final Class<? extends SmsException> smsExceptionClass) {
      this(errorCode, smsExceptionClass, DEFAULT_MESSAGE_KEY);
    }

    private Error(final int errorCode, final Class<? extends SmsException> smsExceptionClass, final String messageKey) {
      this(errorCode);
      this.smsExceptionClass = smsExceptionClass;
      if (StringUtils.isNotBlank(messageKey)) {
        this.messageKey = messageKey;
      }
    }

    public static Error valueOf(Integer errorCode) {
      for (Error error : Error.values()) {
        if (error.getErrorCode() == errorCode) {
          return error;
        }
      }
      throw new IllegalArgumentException("Unmappable Error Code " + errorCode);
    }

    public void performAction(final MessageSourceAccessor messageSourceAccessor, Object... args) throws SmsException {
      if (smsExceptionClass == null) {
        return;
      }

      Validate.notNull(messageSourceAccessor);
      String messageKey = Optional
          .ofNullable(this.messageKey)
          .orElse(DEFAULT_MESSAGE_KEY);
      String message = messageSourceAccessor.getMessage(messageKey, args, "unknown Message Key " + messageKey);

      try {
        throw smsExceptionClass.getConstructor(String.class).newInstance(message);
      } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static class ErrorXmlAdapter extends XmlAdapter<Integer, Error> {
    @Override
    public Error unmarshal(Integer errorCode) throws Exception {
      return Error.valueOf(errorCode);
    }

    @Override
    public Integer marshal(Error error) throws Exception {
      return error.getErrorCode();
    }
  }

}
