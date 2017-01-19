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

import javax.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;
import software.coolstuff.springframework.anysms.model.SmsGateway;

/**
 * @author mufasa1976
 */
@Data
@ConfigurationProperties("any-sms")
class SmsServiceProperties {
  @NotNull
  private String username;
  private String password;
  private SmsGateway defaultGateway;
  @NotNull
  private String sender;
  private boolean test = false;
}
