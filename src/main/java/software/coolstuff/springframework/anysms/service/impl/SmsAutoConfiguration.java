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

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import software.coolstuff.springframework.anysms.service.api.SmsService;

/**
 * @author mufasa1976
 */
@Configuration
@ConditionalOnClass({
    RestTemplateBuilder.class,
    ObjectMapper.class
})
@ConditionalOnProperty(prefix = "any-sms", name = "username")
@EnableConfigurationProperties(SmsServiceProperties.class)
public class SmsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(SmsService.class)
  public SmsService smsService(
      RestTemplateBuilder restTemplateBuilder,
      ObjectMapper objectMapper) {
    return new SmsServiceImpl(restTemplateBuilder, objectMapper);
  }

}
