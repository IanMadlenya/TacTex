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
 */
package edu.utexas.cs.tactex.interfaces;

import java.util.HashMap;
import java.util.List;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.powertac.common.CustomerInfo;
import org.powertac.common.TariffSpecification;

import edu.utexas.cs.tactex.utils.BrokerUtils.ShiftedEnergyData;

public interface ChargeEstimator {

  /**
   * For each customer c, for each tariff c can use 
   * (i.e. 'relevant' tariff), estimate charge and 
   * return a mapping 
   * @param tariffSpecs
   * @param customer2ShiftedEnergy
   * @return
   */
  public abstract HashMap<CustomerInfo, HashMap<TariffSpecification, Double>> estimateRelevantTariffCharges(
      List<TariffSpecification> tariffSpecs, HashMap<CustomerInfo, HashMap<TariffSpecification, ShiftedEnergyData>> customer2ShiftedEnergy);

  public abstract double estimateCharge(ArrayRealVector customerEnergy,
      TariffSpecification spec);

}
