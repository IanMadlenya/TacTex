/*
 * TacTex - a power trading agent that competed in the Power Trading Agent Competition (Power TAC) www.powertac.org
 * Copyright (c) 2013-2016 Daniel Urieli and Peter Stone {urieli,pstone}@cs.utexas.edu               
 *
 *
 * This file is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This file is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * This file incorporates work covered by the following copyright and  
 * permission notice:  
 *
 *     Copyright (c) 2012 by the original author
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package edu.utexas.cs.tactex.core;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.apache.log4j.Logger;
import org.powertac.common.XMLMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Receives incoming jms messages for the broker
 * @author Nguyen Nguyen, John Collins
 */
@Service
public class BrokerMessageReceiver implements MessageListener
{
  static private Logger log = Logger.getLogger(BrokerMessageReceiver.class);
  
  @Autowired
  XMLMessageConverter converter;
  
  @Autowired 
  MessageDispatcher messageDispatcher;

  @Override
  public void onMessage (Message message)
  {
    if (message instanceof TextMessage) {
      try {
        log.debug("onMessage(Message) - receiving a message");
        onMessage(((TextMessage) message).getText());
      } catch (JMSException e) {
        log.error("failed to extract text from TextMessage", e);
      }
    }
  }

  private void onMessage (String xml) {
    log.info("onMessage(String) - received message:\n" + xml);
    Object message = converter.fromXML(xml);
    log.debug("onMessage(String) - received message of type " + message.getClass().getSimpleName());
    messageDispatcher.routeMessage(message);
  }
}
