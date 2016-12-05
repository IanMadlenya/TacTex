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
 *     Copyright 2013-2014 the original author or authors.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an
 *     "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 *     either express or implied. See the License for the specific language
 *     governing permissions and limitations under the License.
 */

package edu.utexas.cs.tactex.servercustomers.common.interfaces;

import org.powertac.common.Broker;

/**
 * Interface that defines the publicly accessible methods
 * a Power TAC balancing market has to implement.
 *
 * @author John Collins
 */
public interface BalancingMarket
{
  /**
   * Returns the market balance for a given broker.
   */
  public double getMarketBalance (Broker broker);

  /**
   * Returns the net regulation energy (positive for up-regulation,
   * negative for down-regulation) for a given broker.
   */
  public double getRegulation (Broker broker);

  /**
   * Returns the slope of cost curve for up-regulation. Total cost for
   * up-regulation by x kwh is pPlus + x * pPlusPrime. Note that x is
   * positive for up-regulation.
   */
  public double getPPlusPrime();

  /**
   * Returns the slope of cost curve for down-regulation. Total cost for
   * down-regulation by x kwh is pMinus + x * pMinusPrime. Note that x is
   * negative for down-regulation.
   */
  public double getPMinusPrime();

  /**
   * Returns the per-timeslot charge for running the balancing market
   */
  public Double getBalancingCost ();

  /**
   * Returns the value used for spot price per MWh if unavailable from 
   * wholesale market. 
   */
  public double getDefaultSpotPrice ();
}