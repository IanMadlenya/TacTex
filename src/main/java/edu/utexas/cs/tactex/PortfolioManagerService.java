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
package edu.utexas.cs.tactex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import org.apache.activemq.transport.stomp.Stomp.Headers.Send;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.log4j.Logger;
import org.joda.time.Instant;
import org.powertac.common.Broker;
import org.powertac.common.Competition;
//import org.powertac.common.ConfigServerBroker;
import org.powertac.common.CustomerInfo;
import org.powertac.common.HourlyCharge;
import org.powertac.common.Rate;
import org.powertac.common.TariffMessage;
import org.powertac.common.TariffSpecification;
import org.powertac.common.TariffTransaction;
import org.powertac.common.TimeService;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.msg.CustomerBootstrapData;
import org.powertac.common.msg.PauseRelease;
import org.powertac.common.msg.PauseRequest;
import org.powertac.common.msg.TariffRevoke;
import org.powertac.common.msg.TariffStatus;
import org.powertac.common.msg.VariableRateUpdate;
import org.powertac.common.repo.CustomerRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import edu.utexas.cs.tactex.interfaces.Activatable;
import edu.utexas.cs.tactex.interfaces.BrokerContext;
import edu.utexas.cs.tactex.interfaces.CostCurvesPredictor;
import edu.utexas.cs.tactex.interfaces.EnergyPredictionManager;
import edu.utexas.cs.tactex.interfaces.Initializable;
import edu.utexas.cs.tactex.interfaces.MarketManager;
import edu.utexas.cs.tactex.interfaces.PortfolioManager;
import edu.utexas.cs.tactex.interfaces.TariffRepoMgr;
import edu.utexas.cs.tactex.utils.BrokerUtils;
import edu.utexas.cs.tactex.utils.MsgVerification;

/**
 * Handles portfolio-management responsibilities for the broker. 
 * @author John Collins
 */
@Service
public class PortfolioManagerService 
implements PortfolioManager, Initializable, Activatable
{
  static private Logger log = Logger.getLogger(PortfolioManagerService.class);
  
  private BrokerContext brokerContext; // master
  
  @Autowired
  private TimeslotRepo timeslotRepo;
  
  @Autowired
  private TariffRepoMgr tariffRepoMgr;
  
  @Autowired
  private CustomerRepo customerRepo;

  @Autowired
  private TimeService timeService;
  
  @Autowired
  private MarketManager marketManager;
  
  @Autowired
  private ContextManagerService contextManager;

  @Autowired  
  private EnergyPredictionManager energyPredictionManager;
  
  @Autowired  
  private CostCurvesPredictor costCurvesPredictor;

  @Autowired
  private ConfiguratorFactoryService configuratorFactoryService;


  private Random randomGen = new Random();

  // parameters
  private static final double DEFAULT_MARGIN = 0.5;
  private static final double FIXED_PER_KWH = -0.06; // 2 * max-distribution-fee = 2 * -0.030
  private static final double DEFAULT_PERIODIC_PAYMENT = -1.0;

  
  // ///////////////////////////////////////////////////
  // FIELDS THAT NEED TO BE INITIALIZED IN initialize()
  // EACH FIELD SHOULD BE ADDED TO test_initialize() !!!
  // ///////////////////////////////////////////////////
  
  // ---- Portfolio records -----
  private HashMap<CustomerInfo, CustomerRecord> customerProfiles;
  // Customer records indexed by power type and by tariff. Note that the
  // CustomerRecord instances are NOT shared between these structures, because
  // we need to keep track of subscriptions by tariff.
  private HashMap<PowerType,
                  HashMap<CustomerInfo, CustomerRecord>> customerProfilesByPowerType;
  private HashMap<TariffSpecification, 
                  HashMap<CustomerInfo, CustomerRecord>> customerSubscriptions;
  private HashMap<PowerType, List<TariffSpecification>> competingTariffs;

  int bootstrapTimeSlotNum; 

  boolean gameStart;
  
  boolean tookAllCrashedAction;

  private TariffSpecification lastPublishedSpec;

  private int lastTimeSpecPublished;

  private int activateTS;


  //private double randomRatio;




  /**
   * Default constructor registers for messages, must be called after 
   * message router is available.
   */
  public PortfolioManagerService ()
  {
    super();
  }


  /**
   * Sets up message handling
   */
  @Override
  @SuppressWarnings("unchecked")
  public void initialize (BrokerContext brokerContext)
  {

    // NEVER CALL ANY SERVICE METHOD FROM HERE, SINCE THEY ARE NOT GUARANTEED
    // TO BE initalize()'d. 
    // Exception: it is OK to call configuratorFactory's public
    // (application-wide) constants


    this.brokerContext = brokerContext;
    customerProfiles = new HashMap<CustomerInfo, CustomerRecord>();
    customerProfilesByPowerType = new HashMap<PowerType,
        HashMap<CustomerInfo, CustomerRecord>>();
    customerSubscriptions = new HashMap<TariffSpecification,
        HashMap<CustomerInfo, CustomerRecord>>();
    competingTariffs = new HashMap<PowerType, List<TariffSpecification>>();
    bootstrapTimeSlotNum = -1; 
    gameStart = true;
    tookAllCrashedAction = false;
    // random tariff undercutting ratio
    //randomRatio = randomGen.nextDouble() * 2; // [0,2]
    //log.info("randomRatio " + randomRatio);
  }
  

  // -------------- Message handlers -------------------
  
  /**
   * Handles CustomerBootstrapData by populating the customer model 
   * corresponding to the given customer and power type. This gives the
   * broker a running start.
   */
  public synchronized void handleMessage (CustomerBootstrapData cbd)
  {
    produceConsume(cbd);
  }


  /**
   * Handles a TariffSpecification. These are sent out when new tariffs are
   * published. If it's not ours, then it's a competitor.
   */
  public synchronized void handleMessage (TariffSpecification spec)
  {
    if (specPublishedByMe(spec)) {
      log.info("published " + spec);
      addOwnTariff(spec); 
    }
    else {
      // otherwise, keep track
      addCompetingTariff(spec);
    }
  }


  /**
   * Handles a TariffRevoke message from the server, indicating that some
   * tariff has been revoked. Should revert what is done by 
   * handleMessage (TariffSpecification)
   */
  public synchronized void handleMessage (TariffRevoke tr)
  {
    Broker revokingBroker = tr.getBroker();

    log.info("Revoke tariff " + tr.getTariffId()
        + " from " + revokingBroker.getUsername());

    TariffSpecification revokedTariffSpec =
        tariffRepoMgr.findSpecificationById(tr.getTariffId());

    if (null == revokedTariffSpec) {
      log.error("Original tariff " + tr.getTariffId() + " not found");        
      return;
    }

    // clean tariff from repo
    tariffRepoMgr.removeRevokedSpec(revokedTariffSpec); 

    // if it's from competitor, remove it from the 
    // competingTariffs list
    if ( ! specPublishedByMe(revokedTariffSpec) ) {
      log.info("clear out competing tariff " + tr.getTariffId());
      getCompetingTariffs(revokedTariffSpec.getPowerType()).
          remove(revokedTariffSpec);
    }
    else {
      customerSubscriptions.remove(revokedTariffSpec);
    }
  }


  /**
   * Handles a TariffStatus message.
   */
  public synchronized void handleMessage (TariffStatus ts)
  {
    log.info("TariffStatus: " + ts.getStatus());
    if (ts.getStatus() != TariffStatus.Status.success) {
      log.error("TariffStatus is not success: " + ts.getStatus());
    }
  }

  
  /**
   * Handles a TariffTransaction. We only care about certain types: PRODUCE,
   * CONSUME, SIGNUP, and WITHDRAW.
   */
  public synchronized void handleMessage(TariffTransaction ttx)
  {

    if ( ! MsgVerification.isLegalTransaction(ttx, tariffRepoMgr, customerRepo) ) {
      return;
    }
    
    TariffTransaction.Type txType = ttx.getTxType();
    if (MsgVerification.customerTransactionTypes.contains(txType)) {

      log.info("Currently ignoring charge and kwh when handling customerTransactionTypes");

      CustomerRecord tariffCustomerRecord = getCustomerRecordByTariff(ttx.getTariffSpec(), ttx.getCustomerInfo());

      if (TariffTransaction.Type.SIGNUP == txType) {
        // keep track of customer counts
        tariffCustomerRecord.signup(ttx.getCustomerCount());
      }
      else if (TariffTransaction.Type.WITHDRAW == txType) {
        // customers presumably found a better deal
        tariffCustomerRecord.withdraw(ttx.getCustomerCount());
      }
      else if (TariffTransaction.Type.PRODUCE == txType) {
        // if ttx count and subscribe population don't match, it will be hard
        // to estimate per-individual production
        if (ttx.getCustomerCount() != tariffCustomerRecord.getSubscribedPopulation()) {
          log.warn("production by subset " + ttx.getCustomerCount() +
              " of subscribed population " + tariffCustomerRecord.getSubscribedPopulation());
        }
        produceConsume(ttx);      
      }
      else if (TariffTransaction.Type.CONSUME == txType) {
        if (ttx.getCustomerCount() != tariffCustomerRecord.getSubscribedPopulation()) {
          log.warn("consumption by subset " + ttx.getCustomerCount() +
              " of subscribed population " + tariffCustomerRecord.getSubscribedPopulation());
        }
        produceConsume(ttx);
      }
      else if (TariffTransaction.Type.PERIODIC == txType) {
        log.debug("need to take care of PERIODIC transaction");
      }
    }
    else {
      if (TariffTransaction.Type.PUBLISH == txType) {
        log.debug("need to take care of PUBLISH transaction");
      }
      else if (TariffTransaction.Type.REVOKE == txType) {
        log.debug("need to take care of REVOKE transaction");
      }
    }
  }


  // --------------- activation -----------------
  /**
   * Note: non-synchronized. It takes time to execute and we don't want it to
   * cause incoming-message drop. We therefore syncronize fragments called from
   * activate. 
   *  (non-Javadoc)
   * @see edu.utexas.cs.tactex.PortfolioManager#activate()
   */
  @Override
  public void activate (int currentTimeslotIndex)
  {
    try {

      log.info("activate, ts " + currentTimeslotIndex);
      
      activateTS = currentTimeslotIndex;

      //if (configuratorFactoryService.isUseInit13()) {

        
        // ====================================
        // the 2013 method for initial tariffs
        // ====================================
        

        if (gameStart) {
          // new game started
          createInitialTariffs();
          gameStart = false;
        }
        else {
          // we have some, are they good enough?
          improveTariffs(currentTimeslotIndex);
          //publishHourlyCharges(currentTimeslotIndex);
        } 
      //}
      //else {
    	//  
      //   
      //  // ====================================
      //  // the 2014? method for initial tariffs
      //  // ====================================
      //
      //  
      //  // TODO later, configure all useCanUse in this file, 
      //  // maybe in one location
      //  boolean useCanUse = true; 
      //
      //  // TODO Remove hard coded and do it using bootstrapTimeSlotNum
      //  if (currentTimeslotIndex == 360) {
      //    // we have no tariffs, default broker's are the only ones we see
      //    createTwoInitialTariffs(useCanUse);
      //  }
      //  else if (currentTimeslotIndex == 365) {
      //    createReactiveInitialTariffs(useCanUse);
      //  }
      //  else {
      //    // we have some, are they good enough?
      //    improveTariffs(currentTimeslotIndex);
      //    publishHourlyCharges(currentTimeslotIndex);
      //  } 
      //}

      log.info("done-activate");


      
    } catch (Throwable e) {
      log.error("caught exception from activate(): ", e);
      //e.printStackTrace();
    } 
  }


  
  // -------------- data access and subroutines ------------------
  
  /**
   * Returns total usage for a given timeslot (represented as a simple index).
   */
  @Override
  public synchronized double collectUsage (int index)
  {
    double result = 0.0;
    for (HashMap<CustomerInfo, CustomerRecord> customerMap : customerSubscriptions.values()) {
      for (CustomerRecord record : customerMap.values()) {
        result += record.getUsage(index, false);
      }
    }
    return -result; // convert to needed energy account balance
  }
  

  /**
   * Note: not synchronized (since might take time) but calls
   * synchronized methods to access potentially shared. Look
   * for *sync* below
   */
  @Override
  public double collectShiftedUsage(int targetTimeslot, int currentTimeslotIndex) {
    double result = 0.0;
    // *sync*: the following two lines call synchronized methods
    HashMap<TariffSpecification, HashMap<CustomerInfo, Double>> tariffSubscriptions = BrokerUtils.revertKeyMapping(BrokerUtils.initializePredictedFromCurrentSubscriptions(BrokerUtils.revertKeyMapping(getCustomerSubscriptions())));
    for (Entry<TariffSpecification, HashMap<CustomerInfo, Integer>> entry : getCustomerSubscriptions().entrySet()) {
      TariffSpecification spec = entry.getKey();
      for (Entry<CustomerInfo, Integer> custInfo2subs : entry.getValue().entrySet()) {
        CustomerInfo cust = custInfo2subs.getKey();
        Integer subs = custInfo2subs.getValue();
        double custSpecSubsShiftedUsage = energyPredictionManager.getShiftedUsageFromBrokerPerspective(
            // <spec,cust> => subs
            spec, cust, subs,
            // current-time => target-time
            targetTimeslot, currentTimeslotIndex,
            tariffSubscriptions);
        result += custSpecSubsShiftedUsage;
      }
    }
    return -result; // convert to needed energy account balance
  }        
  

  private synchronized HashMap<TariffSpecification, HashMap<CustomerInfo, Integer>> getCustomerSubscriptions() {
    HashMap<TariffSpecification, HashMap<CustomerInfo, Integer>> result = 
        new HashMap<TariffSpecification, HashMap<CustomerInfo, Integer>>();

    for (Entry<TariffSpecification, 
        HashMap<CustomerInfo, CustomerRecord>> entry : customerSubscriptions.entrySet()) {

      TariffSpecification spec = entry.getKey();
      HashMap<CustomerInfo, CustomerRecord> oldValue = entry.getValue();

      //if (spec.getPowerType().getGenericType() == genericPowerType) {
      //if (spec.getPowerType() == powerType) {

      // create new map for new tariff spec
      HashMap<CustomerInfo, Integer> newValue = new HashMap<CustomerInfo, Integer>();

      for (Entry<CustomerInfo, CustomerRecord> e : oldValue.entrySet()) {
        // extract subscribed population from record and put it in new value
        newValue.put(e.getKey(), e.getValue().getSubscribedPopulation());        
      }

      result.put(spec, newValue);
      //}
    }
    return result;
  }


  @Override
  public HashMap<TariffSpecification, HashMap<CustomerInfo, Integer>> getCustomerSubscriptions(PowerType powerType) {
    HashMap<TariffSpecification, HashMap<CustomerInfo, Integer>> result = 
        new HashMap<TariffSpecification, HashMap<CustomerInfo, Integer>>();
    
    for (Entry<TariffSpecification, 
            HashMap<CustomerInfo, CustomerRecord>> entry : customerSubscriptions.entrySet()) {
      
      TariffSpecification spec = entry.getKey();
      HashMap<CustomerInfo, CustomerRecord> oldValue = entry.getValue();
      //if (spec.getPowerType().getGenericType() == genericPowerType) {
      if (spec.getPowerType() == powerType) {
        // create new map for new tariff spec
        HashMap<CustomerInfo, Integer> newValue = new HashMap<CustomerInfo, Integer>();
        for (Entry<CustomerInfo, CustomerRecord> e : oldValue.entrySet()) {
          // extract subscribed population from record and put it in new value
          newValue.put(e.getKey(), e.getValue().getSubscribedPopulation());        
        } 
        result.put(spec, newValue);
      }
    }
    return result;
  }


  /**
   * Finds the list of competing tariffs for the given PowerType.
   */
  List<TariffSpecification> getCompetingTariffs (PowerType powerType)
  {
    List<TariffSpecification> result = competingTariffs.get(powerType);
    if (result == null) {
      result = new ArrayList<TariffSpecification>();
      competingTariffs.put(powerType, result);
    }
    return result;
  }


  /**
   * enables getting tariffs of all types that "canUse"
   * the given powerType
   * 
   * @param powerType
   * @return
   */
  List<TariffSpecification> getCompetingTariffsThatCanUse(
      PowerType myPowerType) {
    List<TariffSpecification> result = new ArrayList<TariffSpecification>();
    for (PowerType competingPowerType : competingTariffs.keySet()) {
      if (competingPowerType.canUse(myPowerType)) {
        result.addAll(getCompetingTariffs(competingPowerType));
      }
    } 
    return result;      
  }


  /**
   * enables getting tariffs of all types that "canByUseBy"
   * the given powerType
   * 
   * @param myPowerType
   * @return
   */
  List<TariffSpecification> getCompetingTariffsThatCanBeUsedBy(
      PowerType myPowerType) {
    List<TariffSpecification> result = new ArrayList<TariffSpecification>();
    for (PowerType competingPowerType : competingTariffs.keySet()) {
      if (myPowerType.canUse(competingPowerType)) {
        result.addAll(getCompetingTariffs(competingPowerType));
      }
    } 
    return result;      
  }


  private CustomerRecord getCustomerGeneralRecord(CustomerInfo customer) {
    if (customer == null) {
      log.error("getCustomerGeneralRecord() called with null key");
    }
    CustomerRecord record = customerProfiles.get(customer);
    if (record == null) {
      record = new CustomerRecord(customer);
      customerProfiles.put(customer, record);
    }
    return record;
  }

  
  /**
   * Returns the CustomerRecord for the given type and customer, creating it
   * if necessary.
   */
  private CustomerRecord getCustomerRecordByPowerType (PowerType type,
                                               CustomerInfo customer)
  {
    if (type == null || customer == null) {
      log.error("getCustomerRecordByPowerType() called with null key");
    }

    HashMap<CustomerInfo, CustomerRecord> customerMap =
        customerProfilesByPowerType.get(type);
    if (customerMap == null) {
      customerMap = new HashMap<CustomerInfo, CustomerRecord>();
      customerProfilesByPowerType.put(type, customerMap);
    }
    CustomerRecord record = customerMap.get(customer);
    if (record == null) {
      record = new CustomerRecord(getCustomerGeneralRecord(customer));
      customerMap.put(customer, record);
    }
    return record;
  }


  /**
   * Returns the customer record for the given tariff spec and customer,
   * creating it if necessary. 
   */
  private CustomerRecord getCustomerRecordByTariff (TariffSpecification spec,
                                            CustomerInfo customer)
  {
    if (spec == null || customer == null) {
      log.error("getCustomerRecordByTariff() called with null key");
    }

    HashMap<CustomerInfo, CustomerRecord> customerMap =
        customerSubscriptions.get(spec);
    if (customerMap == null) {
      customerMap = new HashMap<CustomerInfo, CustomerRecord>();
      customerSubscriptions.put(spec, customerMap);
    }
    CustomerRecord record = customerMap.get(customer);
    if (record == null) {
      // seed with the generic record for this customer
      record =
          new CustomerRecord(getCustomerRecordByPowerType(spec.getPowerType(),
                                                          customer));
      customerMap.put(customer, record);
    }
    return record;
  }


  /**
   * @param spec
   */
  private void addOwnTariff(TariffSpecification spec) {
    boolean success = tariffRepoMgr.addToRepo(spec);
    if (success) {
      // instead of:
      // customerSubscriptions.put(spec, new HashMap<CustomerInfo, CustomerRecord>());
      // do:
      // initialize all *relevant* customer entries for this tariff:
      for (CustomerInfo customer : customerRepo.list()){
        // the following is a "retrieve and create if needed" function and
        // therefore it initializes the spec's entry and each relevant
        // customer's entry
        if (customer.getPowerType().canUse(spec.getPowerType())) {
          getCustomerRecordByTariff(spec, customer);
        }
      } 
    }
    else {
      log.error("failed to add published tariff to repo " + spec.getId());
    }
  }


  /**
   * Adds a new competing tariff to the list.
   */
  private void addCompetingTariff (TariffSpecification spec)
  {
    // now we are adding it to the tariffRepoMgr
    boolean success = tariffRepoMgr.addToRepo(spec);
    if ( ! success ) {
      log.error("How come a competing tariff is failed to be added to repo?");
      log.error("competing tariff ignored: " + spec.getId());
      return;
    }
    getCompetingTariffs(spec.getPowerType()).add(spec);
  }


  // Creates initial tariffs for the main power types. These are simple
  // fixed-rate two-part tariffs that give the broker a fixed margin.
  private void createInitialTariffs ()
  {
    double marketPrice = -marketManager.getMeanMarketPricePerKWH();
    // for each power type representing a customer population,
    // create a tariff that's better than what's available 
    double hardCodedBaseRate = ((marketPrice + FIXED_PER_KWH) * (1.0 + DEFAULT_MARGIN)) - 0.000129833;

    double baseRate = 0.75 /*1.0*/ /*2.5*/ /*1.25*/ * hardCodedBaseRate; // market + 2*dist-fee * 1.5 - confusing-term(0). it's -0.129 in the default test
    log.info("THEBASERATE is " + baseRate);
    TariffSpecification spec;
    Rate rate;
    PowerType pt;

    List<TariffSpecification> specsToPublish = new ArrayList<TariffSpecification>();
    
    // %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
    // do not remove these tariff suggestions 
    // %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
    // We currently assume there is at least one tariff we suggest for
    // consumption (and in the future I might assume the same for production,
    // etc.) the assumption is made inside tariff suggestion makers, (and maybe
    // other places)
  
    pt = PowerType.CONSUMPTION;

    //    spec = new TariffSpecification(brokerContext.getBroker(), pt);
    //              //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
    //    rate = new Rate().withValue(baseRate * 1.105);
    //    spec.addRate(rate);
    //    //publishTariffMessage(spec);
    //    specsToPublish.add(spec);
    //  
    //    spec = new TariffSpecification(brokerContext.getBroker(), pt);
    //              //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
    //    rate = new Rate().withValue(baseRate * 1.07);
    //    spec.addRate(rate);
    //    //publishTariffMessage(spec);
    //    specsToPublish.add(spec);
    //
    //    spec = new TariffSpecification(brokerContext.getBroker(), pt);
    //              //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
    //    rate = new Rate().withValue(baseRate * 1.035);
    //    spec.addRate(rate);
    //    //publishTariffMessage(spec);
    //    specsToPublish.add(spec);
    //
    //  
    //    spec = new TariffSpecification(brokerContext.getBroker(), pt);
    //              //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
    //    rate = new Rate().withValue(baseRate * 1); // -0.129 in the default test
    //    spec.addRate(rate);
    //    //publishTariffMessage(spec);
    //    specsToPublish.add(spec);
  
    
    if ( ! configuratorFactoryService.randomizeSpecs() ) {
      spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
      rate = new Rate().withValue(baseRate * 0.965);
      spec.addRate(rate);
      spec = configuratorFactoryService.randomizeSpecs() ? randomizeSpec(spec) : spec;
      //publishTariffMessage(spec);
      specsToPublish.add(spec);


      spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
      rate = new Rate().withValue(baseRate * 0.93);
      spec.addRate(rate);
      spec = configuratorFactoryService.randomizeSpecs() ? randomizeSpec(spec) : spec;
      //publishTariffMessage(spec);
      specsToPublish.add(spec);

      spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
      rate = new Rate().withValue(baseRate * 0.895);
      spec.addRate(rate);
      spec = configuratorFactoryService.randomizeSpecs() ? randomizeSpec(spec) : spec;
      //publishTariffMessage(spec);
      specsToPublish.add(spec);


      spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
      rate = new Rate().withValue(baseRate * 0.86);
      spec.addRate(rate);
      spec = configuratorFactoryService.randomizeSpecs() ? randomizeSpec(spec) : spec;
      //publishTariffMessage(spec);
      specsToPublish.add(spec);


      spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
      rate = new Rate().withValue(baseRate * 0.825);
      spec.addRate(rate);
      spec = configuratorFactoryService.randomizeSpecs() ? randomizeSpec(spec) : spec;
      //publishTariffMessage(spec);
      specsToPublish.add(spec);
    }
    
    if ( ! isCooperativeStrategy() ) {
      // publish all created tariffs
      for (TariffSpecification tariffSpec : specsToPublish) {
        publishTariffMessage(tariffSpec);
      }
    }
    else {
      // cooperative step-1
      spec = new TariffSpecification(brokerContext.getBroker(), pt);
                //.withPeriodicPayment(DEFAULT_PERIODIC_PAYMENT);
      rate = new Rate().withValue(-(0.500 - 0.010 * randomGen.nextDouble())); // -0.129 in the default test
      spec.addRate(rate);
      spec = configuratorFactoryService.randomizeSpecs() ? randomizeSpec(spec) : spec;
      publishTariffMessage(spec);
    }


    if (configuratorFactoryService.isUseSolar()) {
      log.debug("verify production tariffs don't leak into the consumption code");
      pt = PowerType.SOLAR_PRODUCTION;
      log.warn("using hard coded rate for production");
      double ARTIFICIAL_INFLATE = 0.0; // 0.005;
      double productionRate = 0.015; // + 0.002 * randomGen.nextDouble() + ARTIFICIAL_INFLATE;
      spec = new TariffSpecification(brokerContext.getBroker(), pt);
      rate = new Rate().withValue(productionRate);
      spec.addRate(rate);
      publishTariffMessage(spec);
    }
  
    //for (PowerType pt : customerProfiles.keySet()) {
    //// we'll just do fixed-rate tariffs for now
    //double rateValue;
    //if (pt.isConsumption())
    //rateValue = ((marketPrice + fixedPerKwh) * (1.0 + defaultMargin));
    //else
    ////rateValue = (-1.0 * marketPrice / (1.0 + defaultMargin));
    //rateValue = -2.0 * marketPrice;
    //if (pt.isInterruptible())
    //rateValue *= 0.7; // Magic number!! price break for interruptible
    //TariffSpecification spec =
    //new TariffSpecification(brokerContext.getBroker(), pt)
    //.withPeriodicPayment(defaultPeriodicPayment);
    //Rate rate = new Rate().withValue(rateValue);
    //if (pt.isInterruptible()) {
    //// set max curtailment
    //rate.withMaxCurtailment(0.1);
    //}
    //spec.addRate(rate);
    //customerSubscriptions.put(spec, new HashMap<CustomerInfo, CustomerRecord>());
    //tariffRepo.addSpecification(spec);
    //brokerContext.sendMessage(spec);
    //}
    // 
  }


  // Checks to see whether our tariffs need fine-tuning
  private void improveTariffs(int currentTimeslotIndex)
  {
  
    if(bootstrapTimeSlotNum == -1) {
      bootstrapTimeSlotNum = 
          Competition.currentCompetition().getBootstrapDiscardedTimeslots() +
          Competition.currentCompetition().getBootstrapTimeslotCount();
    }
    
    // special case: check if all agents crashed
    if (! tookAllCrashedAction && areAllBrokersCrashed(currentTimeslotIndex) ) {
      executeAllCrashedAction();
      tookAllCrashedAction = true;
      return;
    }
    
    // normal case - no one crashed 
    if (isPublishingSlot(currentTimeslotIndex, PowerType.CONSUMPTION)) {
      log.info("checking whether to publish consumption-tariffs, timeslot " + currentTimeslotIndex);
      log.debug("currently doing check-and-publish-tariffs for all CONSUMPTION together");
      boolean useCanUse = true;
      checkAndPossiblyPublishConsumptionTariff(currentTimeslotIndex, useCanUse);
    } 

    if (isPublishingSlot(currentTimeslotIndex, PowerType.PRODUCTION) &&
        configuratorFactoryService.isUseSolar()) {
      log.info("checking whether to publish production-tariffs, timeslot " + currentTimeslotIndex);
      log.debug("currently doing check-and-publish-tariffs for SOLAR-PRODUCTION only");
      boolean useCanUse = true;
      checkAndPossiblyPublishProductionTariff(currentTimeslotIndex, useCanUse); 
    } 
  }


  /**
   * Revoke all my tariffs. Should be called at most once per game
   */
  private void executeAllCrashedAction() {
    log.warn("executeAllCrashedAction()");
    List<TariffSpecification> mySpecs =
      tariffRepoMgr.findTariffSpecificationsByBroker(brokerContext.getBroker());

    for (TariffSpecification spec: mySpecs) {
      // revoke the old one
      TariffRevoke revoke =
        new TariffRevoke(brokerContext.getBroker(), spec);
      brokerContext.sendMessage(revoke);
    }
    
    // publish extreme specs
    //
    // CONSUMPTION
    TariffSpecification consumptionSpec = new TariffSpecification(brokerContext.getBroker(), PowerType.CONSUMPTION);
    Rate rate = new Rate().withValue(-0.492); // this is what utility-arch chose for this case
    consumptionSpec.addRate(rate);
    publishTariffMessage(consumptionSpec);
    // 
    // SOLAR_PRODUCTION
    if (configuratorFactoryService.isUseSolar()) {
      TariffSpecification productionSpec = new TariffSpecification(brokerContext.getBroker(), PowerType.SOLAR_PRODUCTION);
      rate = new Rate().withValue(0.0155);
      productionSpec.addRate(rate);
      publishTariffMessage(productionSpec);
    }
  }


  private boolean areAllBrokersCrashed(int currentTimeslotIndex) {
    // don't check in the first day
    if (currentTimeslotIndex < 360 + 24) {
      return false;
    }
    
    List<TariffSpecification> otherBrokersTariffs = getAllCompetitorTariffs(true);
    for (TariffSpecification spec : otherBrokersTariffs) {
      if ( ! spec.getBroker().getUsername().equals("default broker") ) {
        // found competing tariff, not all crashed
        return false;
      }
    }

    log.warn("It seems that all agents crashed");
    return true;
  }


  /**
   * We publish only slots right before customers evaluate tariffs
   * @param powerType 
   */
  private boolean isPublishingSlot(int timeslotIndex, PowerType powerType) {
    if (powerType == PowerType.CONSUMPTION) {
      if (timeslotIndex == 360 + 1) {
        return true;
      }
      if (timeslotIndex == 360 + 4) {
        return false;
      } 
      boolean checkWhetherToPublish;
      if (timeslotIndex - bootstrapTimeSlotNum < configuratorFactoryService.initialPublishingPeriod()) {
        checkWhetherToPublish = (timeslotIndex - (bootstrapTimeSlotNum)) % 6 == 4; // not 5 since too close to 6 and might not complete in time
      }
      else {
        checkWhetherToPublish = (timeslotIndex - (bootstrapTimeSlotNum)) % 24 == 4;
      }
      return checkWhetherToPublish;
    }
    
    if (powerType == PowerType.PRODUCTION) {
      if (timeslotIndex == 360 + 2) {
        return false;
      } 
      boolean checkWhetherToPublish;
      if (timeslotIndex - bootstrapTimeSlotNum < configuratorFactoryService.initialPublishingPeriod()) {
        checkWhetherToPublish = (timeslotIndex - (bootstrapTimeSlotNum)) % 6 == 2; // not 1, since subsciptions still updated? not 3 since to close to 4 (cons-tariffs, above)
      }
      else {
        checkWhetherToPublish = (timeslotIndex - (bootstrapTimeSlotNum)) % 24 == 4 + 12;
      }
      return checkWhetherToPublish;

    }
    
    log.warn("unknown power type");
    return false;
  }


  private void checkAndPossiblyPublishConsumptionTariff(int currentTimeslotIndex, boolean useCanUse) {
  
    //if (ConfigServerBroker.isPauseServer()) {
    //  brokerContext.sendMessage(new PauseRequest(brokerContext.getBroker()));
    //}
    
    boolean randomize = configuratorFactoryService.randomizeSpecs();
    
    if ( ! randomize ) {
      
      if ( configuratorFactoryService.isUseUtilityArch() ) {

        // THIS IS OUR NORMAL, STRONGEST STRATEGY for consumption tariffs

        log.info("USING UTILITY-ARCHITECTURE ");

        List<TariffSpecification> competitorTariffs = getAllCompetitorTariffs(useCanUse);


        // CHANGESOLAR
        HashMap<TariffSpecification, HashMap<CustomerInfo, Integer>> 
          tariffSubscriptions = getCustomerSubscriptions(/*PowerType.CONSUMPTION*/);    



        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //
        // LATTE: 
        // The following function call encapsulates lines 2-26 of the LATTE
        // algorithm (for consumption tariffs). 
        //
        // Note: LATTE's subroutine names do not directly correspond
        // to function names in this code.
        //
        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ 
        //
        //log.info("checkAndPossiblyPublishConsumptionTariff() "  + timeslotIndex + " " + (useCanUse ? "" : "not") + " using canUse");
        List<TariffMessage> tariffActions = 
          configuratorFactoryService.getConsumptionTariffActionGenerator().selectTariffActions(useCanUse,
              tariffSubscriptions, competitorTariffs, marketManager, contextManager, 
              costCurvesPredictor,
              currentTimeslotIndex, 
              configuratorFactoryService.isUseRevoke(),
              brokerContext.getBroker());
        // add 4 tariffs if it's second publication period and we are 'cooperative'
        if ( currentTimeslotIndex < (360 + 6) &&                             // first step utility-arch used
            isCooperativeStrategy() && // using 'cooperative' first-step with single (rather than 5) tariffs
            tariffActions.size() != 0 &&                                     // utility-arch recommends publishing
            tariffActions.get(0).getClass() == TariffSpecification.class ) { // should always be true in timestep < 366
          for (int i = 1; i <= 4; ++i) {
            TariffSpecification spec = new TariffSpecification(brokerContext.getBroker(), PowerType.CONSUMPTION);
            double recommendedRate = ((TariffSpecification)tariffActions.get(0)).getRates().get(0).getValue();
            //double zeroCenteredRandom = randomGen.nextDouble() - 0.5;
            //Rate rate = new Rate().withValue(recommendedRate + 0.0005 * zeroCenteredRandom); // -0.129 in the default test
            Rate rate = new Rate().withValue(recommendedRate + i * 0.001 + randomGen.nextDouble() * 0.00001  /*<= that's 0.001*/); // -0.129 in the default test
            spec.addRate(rate);
            tariffActions.add(spec);
          }
        } 
        log.info("number of suggested specs: (spec-actions):" + tariffActions.size());


        for (TariffMessage action : tariffActions) {
          publishTariffMessage(action);
        } 
      }
      else { 
        // not using utility architecture - using a baseline strategy
        
        log.warn("USING UNDERCUTTING ");
        //////////////////////////////////////////////////////
        //UNDERCUTTING STRATEGY
        //
        //// publish 80%-95% between market and competing
        PowerType pt = PowerType.CONSUMPTION;
        double avgMktPrice = -marketManager.getMeanMarketPricePerKWH();
        double priceLowerBound = avgMktPrice + contextManager.getDistributionFee();
        double competingMinRateValue = getCompetingMinRateValue(pt, priceLowerBound);
        double myMinRateValue = getMyMinRateValue(pt);
        if (-priceLowerBound < -competingMinRateValue &&  
            -competingMinRateValue <= -myMinRateValue ) {
          TariffSpecification spec = new TariffSpecification(brokerContext.getBroker(), pt);
          double randomElem = 0.8 + randomGen.nextDouble() * (0.95-0.8);
          double rateValue = avgMktPrice +  randomElem * (competingMinRateValue - avgMktPrice);
          Rate rate = new Rate().withValue(rateValue);
          spec.addRate(rate);
          publishTariffMessage(spec);
        }
        //
        //END - UNDERCUTTING STRATEGY 
        //////////////////////////////////////////////////////
      }
    }
    else { // randomizing..

      log.warn("USING RANDOMIZE ");

      // ///////////////////////////////////////////////////////////////////
      // THIS IS A STRATEGY FOR CONFUSING THE OPPONENTS DURING QUALIFICATION
      // ///////////////////////////////////////////////////////////////////

      // Computing some prelimnary values needed for any 
      // strategy below
      PowerType pt = PowerType.CONSUMPTION;
      //log.info("PowerType " + pt);
      //
      //// get competing min rate
      double avgMktPrice = -marketManager.getMeanMarketPricePerKWH();
      log.info("avgMktPrice " + avgMktPrice + " timeslot " + currentTimeslotIndex);
      double priceLowerBound = avgMktPrice /*ignore dist-fee so I lose money..*/;//  + contextManager.getDistributionFee();
      log.info("priceLowerBound " + priceLowerBound );
      double competingMinRateValue = getCompetingMinRateValue(pt, priceLowerBound);
      log.info("competing min rate " + competingMinRateValue);
      double myMinRateValue = getMyMinRateValue(pt);
      log.info("myMinRateValue " + myMinRateValue);
      //      
      if (-priceLowerBound < -competingMinRateValue &&  // TODO: fix using '-' to something better
        -competingMinRateValue <= -myMinRateValue * 1.1 /* even if above me, within 10%*/ ) {
      
      log.info("publishing");
      //////////////////////////////////////////////////////
      //UNDERCUTTING STRATEGY
      //
      //// publish 66% between market and competing
      TariffSpecification spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //// .withPeriodicPayment(defaultPeriodicPayment);
      double randomElem = 0.4 + randomGen.nextDouble() * (0.7-0.4);
      double rateValue = avgMktPrice +  randomElem * (competingMinRateValue - avgMktPrice);
      ////double rateValue = avgMktPrice + randomRatio * (competingMinRateValue - avgMktPrice);
      Rate rate = new Rate().withValue(rateValue);
      //// if interuptible, improve curtailement ratio
      //if (pt.isInterruptible()) {
      //double minCurtailRatio = getMinCurtailRatio(pt);
      //// TODO: but note that currently I am not using curtailment
      //rate.withMaxCurtailment(minCurtailRatio * 0.95); 
      //}
      spec.addRate(rate);
      spec = configuratorFactoryService.randomizeSpecs() ? randomizeSpec(spec) : spec;
      publishTariffMessage(spec);
      //
      //END - UNDERCUTTING STRATEGY
      //
      //////////////////////////////////////////////////////
      //Publish intermeidate rate between undercutting and competing
      //////////////////////////////////////////////////////
      //// publish additional rate between the competing and the low
      //spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //// .withPeriodicPayment(defaultPeriodicPayment);
      //rateValue = avgMktPrice + (randomRatio + 0.5 * (1 - randomRatio) ) * (competingMinRateValue - avgMktPrice);
      //rate = new Rate().withValue(rateValue);
      //// if interuptible, improve curtailement ratio
      //if (pt.isInterruptible()) { //double minCurtailRatio = getMinCurtailRatio(pt);
      //// TODO: but note that currently I am not using curtailment
      //rate.withMaxCurtailment(minCurtailRatio * 0.95); 
      //}
      //spec.addRate(rate);
      //publishTariffMessage(spec);
      //
      //END Publish intermeidate rate between undercutting and competing
      //////////////////////////////////////////////////////
      //
      //////////////////////////////////////////////////////
      //SURROUNDING STRATEGY
      //
      //TariffSpecification spec;
      //Rate rate; 
      //
      //spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //// .withPeriodicPayment(defaultPeriodicPayment);
      //double rateValue = avgMktPrice + 0.97 * (competingMinRateValue - avgMktPrice);
      ////double rateValue = avgMktPrice + randomRatio * (competingMinRateValue - avgMktPrice);
      //rate = new Rate().withValue(rateValue);
      //// if interuptible, improve curtailement ratio
      //if (pt.isInterruptible()) {
      //double minCurtailRatio = getMinCurtailRatio(pt);
      //// TODO: but note that currently I am not using curtailment
      //rate.withMaxCurtailment(minCurtailRatio * 0.95); 
      //}
      //spec.addRate(rate);
      //publishTariffMessage(spec);
      //
      //// publish additional rate above the competing
      //spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //// .withPeriodicPayment(defaultPeriodicPayment);
      //rateValue = avgMktPrice + 1.015364 * (competingMinRateValue - avgMktPrice);
      //rate = new Rate().withValue(rateValue);
      //// if interuptible, improve curtailement ratio
      //if (pt.isInterruptible()) {
      //double minCurtailRatio = getMinCurtailRatio(pt);
      //// TODO: but note that currently I am not using curtailment
      //rate.withMaxCurtailment(minCurtailRatio * 0.95); 
      //}
      //spec.addRate(rate);
      //publishTariffMessage(spec);
      //
      //// publish additional rate above the competing
      //spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //// .withPeriodicPayment(defaultPeriodicPayment);
      //rateValue = avgMktPrice + 1.06 * (competingMinRateValue - avgMktPrice);
      //rate = new Rate().withValue(rateValue);
      //// if interuptible, improve curtailement ratio
      //if (pt.isInterruptible()) {
      //double minCurtailRatio = getMinCurtailRatio(pt);
      //// TODO: but note that currently I am not using curtailment
      //rate.withMaxCurtailment(minCurtailRatio * 0.95); 
      //}
      //spec.addRate(rate);
      //publishTariffMessage(spec);
      //
      //spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //// .withPeriodicPayment(defaultPeriodicPayment);
      //rateValue = avgMktPrice + 1.10 * (competingMinRateValue - avgMktPrice);
      //rate = new Rate().withValue(rateValue);
      //// if interuptible, improve curtailement ratio
      //if (pt.isInterruptible()) {
      //double minCurtailRatio = getMinCurtailRatio(pt);
      //// TODO: but note that currently I am not using curtailment
      //rate.withMaxCurtailment(minCurtailRatio * 0.95); 
      //}
      //spec.addRate(rate);
      //publishTariffMessage(spec);
      //
      //
      //END - SURROUNDING STRATEGY
      //////////////////////////////////////////////////////
      //
      //////////////////////////////////////////////////////
      //TOU 1, 1.1, 1   1.1, 1, 1.1
      //////////////////////////////////////////////////////
      //double rateValue = avgMktPrice + 0.99209218347182712 * (competingMinRateValue - avgMktPrice);
      //
      //TariffSpecification spec; 
      //Rate rate1, rate2, rate3;
      //rate1 = new Rate().withValue(rateValue).withDailyBegin(0).withDailyEnd(7);
      //rate2 = new Rate().withValue(1.1 * rateValue).withDailyBegin(8).withDailyEnd(17);
      //rate3 = new Rate().withValue(rateValue).withDailyBegin(17).withDailyEnd(23);
      //spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //spec.addRate(rate1).addRate(rate2).addRate(rate3);
      //publishTariffMessage(spec);
      //
      //rate1 = new Rate().withValue(1.1 * rateValue).withDailyBegin(0).withDailyEnd(7);
      //rate2 = new Rate().withValue(rateValue).withDailyBegin(8).withDailyEnd(17);
      //rate3 = new Rate().withValue(1.1 * rateValue).withDailyBegin(17).withDailyEnd(23);
      //spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //spec.addRate(rate1).addRate(rate2).addRate(rate3);
      //publishTariffMessage(spec);
      //
      //END - TOU 1, 1.1, 1   1.1, 1, 1.1
      //////////////////////////////////////////////////////
      //
      //
      //// publish the competing rate
      //spec = new TariffSpecification(brokerContext.getBroker(), pt);
      //// .withPeriodicPayment(defaultPeriodicPayment);
      //rateValue = competingMinRateValue;
      //rate = new Rate().withValue(rateValue);
      //// if interuptible, improve curtailement ratio
      //if (pt.isInterruptible()) {
      //double minCurtailRatio = getMinCurtailRatio(pt);
      //// TODO: but note that currently I am not using curtailment
      //rate.withMaxCurtailment(minCurtailRatio * 0.95); 
      //}
      //spec.addRate(rate);
      //publishTariffMessage(spec);
      //
      //
      //VARIABLE RATE
      //////////////////////////////////////////////////////
      //// publish 91% between market and competing
      //TariffSpecification vrspec = new TariffSpecification(brokerContext.getBroker(), pt);
      //// .withPeriodicPayment(defaultPeriodicPayment);
      //double rateValue = avgMktPrice + 0.911234 * (competingMinRateValue - avgMktPrice);
      //double minValue = avgMktPrice;
      //double expectedMean = rateValue;
      //double maxValue = rateValue + (rateValue - avgMktPrice); // TODO: assuming min <= rate <= max
      //Rate vrate = new Rate().withNoticeInterval(3)
      //.withFixed(false)
      //.withMinValue(minValue)
      //.withExpectedMean(expectedMean)
      //.withMaxValue(maxValue);
      //vrspec.addRate(vrate);
      //publishTariffMessage(vrspec);
      //
      //END - VARIABLE RATE
      //////////////////////////////////////////////////////
      }
    }


    //if (ConfigServerBroker.isPauseServer()) {
    //  brokerContext.sendMessage(new PauseRelease(brokerContext.getBroker()));
    //}

  }


  private boolean isCooperativeStrategy() {
    boolean isCoop = BrokerUtils.getNumberOfBrokers() <= configuratorFactoryService.getCoopMaxBrkrs(); 
    log.debug("is cooperative strategy: " + isCoop);
    return isCoop;
  }


  private void checkAndPossiblyPublishProductionTariff(int currentTimeslotIndex, boolean useCanUse) {
    
    //if (ConfigServerBroker.isPauseServer()) {
    //  brokerContext.sendMessage(new PauseRequest(brokerContext.getBroker()));
    //}
    
    if ( configuratorFactoryService.isUseUtilityArch() ) {

      // THIS IS OUR NORMAL, STRONG STRATEGY for production tariffs

      log.info("USING UTILITY-ARCHITECTURE ");

      List<TariffSpecification> competitorTariffs = getAllCompetitorTariffs(useCanUse);

      // CHANGESOLAR
      HashMap<TariffSpecification, HashMap<CustomerInfo, Integer>> 
        tariffSubscriptions = getCustomerSubscriptions(/*PowerType.CONSUMPTION*/);    

      //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
      //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
      //
      // LATTE: 
      // The following function call encapsulates lines 2-26 of the LATTE
      // algorithm (for production tariffs). 
      //
      // Note: LATTE's subroutine names do not directly correspond
      // to function names in this code.
      //
      //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
      //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ 
      //
      //log.info("checkAndPossiblyPublishConsumptionTariff() "  + timeslotIndex + " " + (useCanUse ? "" : "not") + " using canUse");
      List<TariffMessage> tariffsToPublish = 
        configuratorFactoryService.getProductionTariffActionGenerator().selectTariffActions(useCanUse,
            tariffSubscriptions, competitorTariffs, marketManager, contextManager, 
            costCurvesPredictor,
            currentTimeslotIndex, 
            configuratorFactoryService.isUseRevoke(),
            brokerContext.getBroker());
      log.info("number of suggested specs: " + tariffsToPublish.size());

      for (TariffMessage action : tariffsToPublish) {
        publishTariffMessage(action);
      } 
    }

    
    //if (ConfigServerBroker.isPauseServer()) {
    //  brokerContext.sendMessage(new PauseRelease(brokerContext.getBroker()));
    //}
  }


  private synchronized List<TariffSpecification> getAllCompetitorTariffs(boolean useCanUse) {
    // CHANGESOLAR
    Set<TariffSpecification> competitorTariffSet = new HashSet<TariffSpecification>();
    if (useCanUse) {
      competitorTariffSet.addAll(getCompetingTariffsThatCanUse(PowerType.CONSUMPTION));
      competitorTariffSet.addAll(getCompetingTariffsThatCanBeUsedBy(PowerType.CONSUMPTION));
      competitorTariffSet.addAll(getCompetingTariffsThatCanUse(PowerType.SOLAR_PRODUCTION));
      competitorTariffSet.addAll(getCompetingTariffsThatCanBeUsedBy(PowerType.SOLAR_PRODUCTION));
    }
    else {
      competitorTariffSet.addAll(getCompetingTariffs(PowerType.CONSUMPTION));
      competitorTariffSet.addAll(getCompetingTariffs(PowerType.SOLAR_PRODUCTION));
    }
    List<TariffSpecification> competitorTariffs = new ArrayList<TariffSpecification>();
    competitorTariffs.addAll(competitorTariffSet);
    return competitorTariffs;
  }


  /**
   * @param spec
   * @return
   */
  private TariffSpecification randomizeSpec(TariffSpecification spec) {
    double rateValue = spec.getRates().get(0).getValue();
    PowerType pt = spec.getPowerType();

    int rnd = randomGen.nextInt(4);

    if (rnd == 0) {
      return spec; // no randomization, fixed rate
    }
    else if (rnd == 1) {
      // TOU 1, 1.1, 1
      TariffSpecification newspec; 
      Rate rate1, rate2, rate3;
      rate1 = new Rate().withValue(rateValue).withDailyBegin(0).withDailyEnd(7);
      rate2 = new Rate().withValue(1.001 * rateValue).withDailyBegin(8).withDailyEnd(17);
      rate3 = new Rate().withValue(rateValue).withDailyBegin(18).withDailyEnd(23);
      newspec = new TariffSpecification(brokerContext.getBroker(), pt);
      newspec.addRate(rate1).addRate(rate2).addRate(rate3);
      return newspec;
    }

    else if (rnd == 2) {
      // TOU 1.1, 1, 1.1
      TariffSpecification newspec; 
      Rate rate1, rate2, rate3;
      rate1 = new Rate().withValue(1.001 * rateValue).withDailyBegin(0).withDailyEnd(7);
      rate2 = new Rate().withValue(rateValue).withDailyBegin(8).withDailyEnd(17);
      rate3 = new Rate().withValue(1.001 * rateValue).withDailyBegin(18).withDailyEnd(23);
      newspec = new TariffSpecification(brokerContext.getBroker(), pt);
      newspec.addRate(rate1).addRate(rate2).addRate(rate3);
      return newspec;
      
    }

    else {
      //VARIABLE RATE
      //////////////////////////////////////////////////////
      // publish 91% between market and competing
      TariffSpecification vrspec = new TariffSpecification(brokerContext.getBroker(), pt);
      double minValue = rateValue * 0.999;
      double expectedMean = rateValue;
      double maxValue = rateValue * 1.001;
      Rate vrate = new Rate().withNoticeInterval(3)
        .withFixed(false)
        .withMinValue(minValue)
        .withExpectedMean(expectedMean)
        .withMaxValue(maxValue);
      vrspec.addRate(vrate);
      return vrspec;

      //END - VARIABLE RATE
      //////////////////////////////////////////////////////
    }
  }


  private <T> void printSubscriptions(
      HashMap<TariffSpecification,HashMap<CustomerInfo,T>> customerSubscriptions2) {
    for (TariffSpecification spec : customerSubscriptions2.keySet()) {
      log.info("ps TariffSpec: " + spec);
      for (CustomerInfo c : customerSubscriptions2.get(spec).keySet()) {
        log.info("ps customer " + c + " subscriptions " + customerSubscriptions2.get(spec).get(c));
      }
    }    
  }


  /**
   * 
   * This method updates the {@link CustomerRecord} in all
   * the relevant HashMaps. Bootstrap data normally includes
   * timeslots {24, 25,...,359} 
   * 
   * @param cbd
   * 
   */
  private void produceConsume(CustomerBootstrapData cbd) {
    
    PowerType powerType = cbd.getPowerType();
    String customerName = cbd.getCustomerName();
    
    CustomerInfo customer =
            customerRepo.findByNameAndPowerType(customerName,
                                                powerType);
  
    
    // NOTE: in general it's safer to get the more specific record first, since
    // specific records are initialized with more generic ones, so we don't
    // want a double update to happen.  Specifically, currently perhaps it
    // doesn't matter because this is the start of the game.
    CustomerRecord powerTypeRecord = getCustomerRecordByPowerType(powerType, customer);
    CustomerRecord generalRecord = getCustomerGeneralRecord(customer);
    // - what is offset: it is the first 24 hours that are ignored
    // - Bootstrap data normally includes timeslots {24, 25,...,359} 
    // - normally 24 timeslots are discarded and the first timeslot in bootstrap record is 24
    int offset = Competition.currentCompetition().getBootstrapDiscardedTimeslots(); 
    for (int i = 0; i < cbd.getNetUsage().length; i++) {
      int bootstrapTimeslot = i + offset;
      powerTypeRecord.produceConsume(cbd.getNetUsage()[i], customer.getPopulation(), bootstrapTimeslot, true);
      generalRecord.produceConsume(cbd.getNetUsage()[i], customer.getPopulation(), bootstrapTimeslot, true);
    }
  }


  /**
   * @param ttx
   */
  private void produceConsume(TariffTransaction ttx) {
    
    // defensive sanity check
    TariffTransaction.Type txType = ttx.getTxType();
    if( TariffTransaction.Type.CONSUME != txType &&
        TariffTransaction.Type.PRODUCE != txType ) {
      log.warn("produceConsume is called with the wrong type of transaction - ignoring...");
      return;
    }
      
    CustomerInfo customer = ttx.getCustomerInfo();
    TariffSpecification tariffSpec = ttx.getTariffSpec();    
    int customerCount = ttx.getCustomerCount();
    int postedTime = ttx.getPostedTimeslotIndex();
    PowerType powerType = tariffSpec.getPowerType();
    double kWh = ttx.getKWh();
    // IMPORTANT: produceConsume must be called from the most specific to the
    // most general, since if a specific record doesn't exist - it is
    // initialized from the more generic one, so any other ordering would
    // result in double updates for the more specific records
    CustomerRecord tariffRecord = getCustomerRecordByTariff(tariffSpec, customer);    
    tariffRecord.produceConsume(kWh, customerCount, postedTime, true /*hack, true means update both*/);     
    
    // update general records only if fixed-rate 
    // tariff (want them to hold default profile)
    //
    boolean fixed = false;
    if (tariffSpec.getRates().size() == 1 && tariffSpec.getRates().get(0).isFixed()) {
      fixed = true; 
    }
      
    CustomerRecord profileRecord = getCustomerRecordByPowerType(powerType, customer);
    profileRecord.produceConsume(kWh, customerCount, postedTime, fixed);

    CustomerRecord generalRecord = getCustomerGeneralRecord(customer);
    generalRecord.produceConsume(kWh, customerCount, postedTime, fixed);
  }


  /*
   * This function is unused on TacTex's normal strategy, which doesn't use
   * variable rate tariffs
   */
  private void publishHourlyCharges(int currentTimeslotIndex) {
    for(TariffSpecification spec : 
          tariffRepoMgr.findTariffSpecificationsByPowerType(PowerType.CONSUMPTION)) {
      // scan only my tariffs
      if (specPublishedByMe(spec)) {
        for(Rate r : spec.getRates()){
          if(r.isFixed() == false) {
            // need to send a hourly charge
            long noticeInterval = r.getNoticeInterval();
            int destinationTimeslot = (int) (currentTimeslotIndex + noticeInterval + 1);// + 1 just in case
            log.info("DU noticeInterval " + noticeInterval + " currentTimeslot " + currentTimeslotIndex + " destinationTimeslot " + destinationTimeslot );
            double marketPrice = -marketManager.getMarketAvgPricePerSlotKWH(destinationTimeslot); 
            log.info("DU marketPrice " + marketPrice); 
            double minValue = Math.abs(r.getMinValue());
            double maxValue = Math.abs(r.getMaxValue());
            double rateValue = Math.abs(marketPrice + r.getExpectedMean() - minValue); 
            log.info("DU minValue " + minValue + " maxValue " + maxValue + " rateValue " + rateValue);
            // trim if needed
            rateValue = Math.min(Math.max(rateValue, minValue), maxValue);
            log.info("DU rateValue after trimming  " + rateValue);
            rateValue = -rateValue; // this is the rate's sign
            log.info("DU final rateValue " + rateValue);
            HourlyCharge h = 
              new HourlyCharge(timeService.getCurrentTime().plus((noticeInterval + 1)* TimeService.HOUR) , rateValue);
            r.addHourlyCharge(h);
            h.setRateId(r.getId());
            VariableRateUpdate v = 
              new VariableRateUpdate(brokerContext.getBroker(), r, h);
            brokerContext.sendMessage(v);
          }
        }
      }
    }
  }


  private boolean specPublishedByMe(TariffSpecification spec) {
    return spec.getBroker().getUsername().equals(brokerContext.getBrokerUsername());
  }


  private void publishTariffMessage(TariffMessage action) {
    
    if (action.getClass() == TariffSpecification.class) {
      lastPublishedSpec = (TariffSpecification)action;
      lastTimeSpecPublished = activateTS;
    }
    
    brokerContext.sendMessage(action);
  }


  private double getCompetingMinRateValue(PowerType pt, double priceLowerBound) {
    // rates are negative (i.e. from customer's perspective) so I actually want
    // the highest one, which means the highest from customer perspective
    double competingMinRateValue = -Double.MAX_VALUE;
    for (TariffSpecification competingTariff : getCompetingTariffsThatCanUse(pt)){
      Broker theBroker = competingTariff.getBroker();
      if ( ! specPublishedByMe(competingTariff) ) {
        for (Rate r : competingTariff.getRates()) {
          double value;
          if (r.isFixed()) {
            value = r.getMinValue();
          }
          else {
            value = r.getExpectedMean();
          }
          // we ignore prices below market
          if(priceLowerBound > value && value > competingMinRateValue) {
            competingMinRateValue = value;
          }
        }
      }
    }
    return competingMinRateValue;
  }


  private double getCompetingMaxProdRateValue(PowerType pt, double rateUpperBound) {
    // rates are positive (i.e. from customer's perspective) so I want
    // the highest one - the best for the customer
    double competingMaxProdRateValue = 0; 
    for (TariffSpecification competingTariff : getCompetingTariffs(pt)){
      if ( ! specPublishedByMe(competingTariff) ) {
        for (Rate r : competingTariff.getRates()) {
          double value = r.getMinValue();
          // we ignore prices below market
          if(rateUpperBound > value && value > competingMaxProdRateValue) {
            competingMaxProdRateValue = value;
          }
        }
      }
    }
    return competingMaxProdRateValue;
  }


  private double getMyMinRateValue(PowerType pt) {
    // rates are negative (i.e. from customer's perspective) so I actually want
    // the highest one, which means the highest from customer perspective.
    double myMinRateValue = -Double.MAX_VALUE;
    for(TariffSpecification spec : 
          tariffRepoMgr.findTariffSpecificationsByPowerType(pt)) {
      if(specPublishedByMe(spec)) {
        for (Rate r : spec.getRates()) {
          double value;
          if (r.isFixed()) {
            value = r.getMinValue();
          }
          else {
            value = r.getExpectedMean();
          }
          if(value > myMinRateValue) {
            myMinRateValue = value;
          }
        }
      }
    }
    return myMinRateValue;
  }


  private double getMyMaxProdRateValue(PowerType pt) {
    // rates are positive (i.e. from customer's perspective) so I actually want
    // the highest one - the best for the customer
    double myMaxRateValue = -Double.MAX_VALUE;
    for(TariffSpecification spec : 
          tariffRepoMgr.findTariffSpecificationsByPowerType(pt)) {
      if(specPublishedByMe(spec)) {
        for (Rate r : spec.getRates()) {
          double value = r.getMinValue();
          if(value > myMaxRateValue) {
            myMaxRateValue = value;
          }
        }
      }
    }
    return myMaxRateValue;
  }


  private double getMinCurtailRatio(PowerType pt) {
    double minCurtailRatio = Double.MAX_VALUE;
    for (TariffSpecification competingTariff : getCompetingTariffs(pt)){
      for (Rate r : competingTariff.getRates()) {
        double value = r.getMaxCurtailment();
        if(value < minCurtailRatio && 0 <= value && value <= 1) {
          minCurtailRatio = value;
        }
      }
    }
    return minCurtailRatio;
  }

    
  // ------------- test-support methods ----------------
  double getUsageForCustomer (CustomerInfo customer,
                              TariffSpecification tariffSpec,
                              int index)
  {
    CustomerRecord record = getCustomerRecordByTariff(tariffSpec, customer);
    return record.getUsage(index, false);
  }

  
  //test-support method
  ArrayRealVector getRawUsageForCustomerByTariff (CustomerInfo customer, TariffSpecification spec)
  {    
    return getCustomerRecordByTariff(spec, customer).getUsageArray(false);
  }

 
  // test-support method
  HashMap<PowerType, double[]> getRawUsageForCustomerByPowerType (CustomerInfo customer)
  {
    HashMap<PowerType, double[]> result = new HashMap<PowerType, double[]>();
    for (PowerType type : customerProfilesByPowerType.keySet()) {
      CustomerRecord record = customerProfilesByPowerType.get(type).get(customer);
      if (record != null) {
        result.put(type, record.usage);
      }
    }
    return result;
  }
  

  public synchronized ArrayRealVector getGeneralRawUsageForCustomer (CustomerInfo customer, boolean fixed)
  {    
    return getCustomerGeneralRecord(customer).getUsageArray(fixed);
  }
  

  // test-support method
  HashMap<String, Integer> getCustomerCounts()
  {
    HashMap<String, Integer> result = new HashMap<String, Integer>();
    for (TariffSpecification spec : customerSubscriptions.keySet()) {
      HashMap<CustomerInfo, CustomerRecord> customerMap = customerSubscriptions.get(spec);
      for (CustomerRecord record : customerMap.values()) {
        result.put(record.customer.getName() + spec.getPowerType(), 
                    record.getSubscribedPopulation());
      }
    }
    return result;
  }


  //-------------------- Customer-model recording ---------------------
  /**
   * Keeps track of customer status and usage. Usage is stored
   * per-customer-unit, but reported as the product of the per-customer
   * quantity and the subscribed population. This allows the broker to use
   * historical usage data as the subscribed population shifts.
   */
  public class CustomerRecord
  {
    private CustomerInfo customer;
    private int subscribedPopulation = 0;
    private double[] usage;
    private double[] fixedusage;
    private double alpha = 0.3;
    
    /**
     * Creates an empty record
     */
    CustomerRecord (CustomerInfo customer)
    {
      super();
      this.customer = customer;
      this.usage = new double[configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH()];
      this.fixedusage = new double[configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH()];
    }
    
    CustomerRecord (CustomerRecord oldRecord)
    {
      super();
      this.customer = oldRecord.customer;
      this.usage = Arrays.copyOf(oldRecord.usage, configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH());
      this.fixedusage = Arrays.copyOf(oldRecord.fixedusage, configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH());
    }
    
    // Returns the CustomerInfo for this record
    CustomerInfo getCustomerInfo ()
    {
      return customer;
    }

    ArrayRealVector getUsageArray(boolean fixed) {
      if (fixed)
        return new ArrayRealVector(fixedusage);
      else
        return new ArrayRealVector(usage);
    }
    
    // Adds new individuals to the count
    void signup (int population)
    {
      subscribedPopulation = Math.min(customer.getPopulation(),
                                      subscribedPopulation + population);
    }
    
    // Removes individuals from the count
    void withdraw (int population)
    {
      subscribedPopulation -= population;
      if (subscribedPopulation < 0) {
        log.error("subscribed population < 0: " + subscribedPopulation + ", resetting to 0");
        subscribedPopulation = 0;
      }
    }
    
    // Customer produces or consumes power. We assume the kwh value is negative
    // for production, positive for consumption
    //void produceConsume (double kwh, int population, Instant when)
    //{
    //  int index = getIndex(when);
    //  produceConsume(kwh, population, index);
    //}
    
    // The usage length is one week: 7*24, so the second week enters into
    // similar slots as the first week, and a slot is updated using an
    // exponential smoothing
    //
    // store profile data at the given index
    void produceConsume (double kwh, int population, int rawIndex, boolean fixed)
    {
      log.debug("produce consume is averaging regardless of the number of customers");
      int index = getIndex(rawIndex);
      double kwhPerCustomer = kwh / (double)population;
      double oldUsage = usage[index];
      if (oldUsage == 0.0) {
        // assume this is the first time
        usage[index] = kwhPerCustomer;
      }
      else {
        // exponential smoothing
        usage[index] = alpha * kwhPerCustomer + (1.0 - alpha) * oldUsage;
      }
      if (fixed) {
        double oldUsage1 = fixedusage[index];
        if (oldUsage1 == 0.0) {
          // assume this is the first time
          fixedusage[index] = kwhPerCustomer;
        }
        else {
          // exponential smoothing
          fixedusage[index] = alpha * kwhPerCustomer + (1.0 - alpha) * oldUsage1;
        }
      }
    }

    
    double getUsage (int index, boolean fixed)
    {
      if (index < 0) {
        log.warn("usage requested for negative index " + index);
        index = 0;
      }
      if (fixed)
        return (fixedusage[getIndex(index)] * (double)subscribedPopulation);
      else 
        return (usage[getIndex(index)] * (double)subscribedPopulation);
    }
    

    // we assume here that timeslot index always matches the number of
    // timeslots that have passed since the beginning of the simulation.
    int getIndex (Instant when)
    {
      int result = (int)((when.getMillis() - timeService.getBase()) /
                         (Competition.currentCompetition().getTimeslotDuration()));
      return result;
    }
    

    private int getIndex (int rawIndex)
    {
      return rawIndex % usage.length;
    }


    public int getSubscribedPopulation() {
      return subscribedPopulation;
    }
  }  
}
