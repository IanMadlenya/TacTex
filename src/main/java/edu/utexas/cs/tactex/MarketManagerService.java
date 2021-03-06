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
 *     Copyright (c) 2012-2013 by the original author
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
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeMap;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.log4j.Logger;
import org.powertac.common.BalancingTransaction;
import org.powertac.common.ClearedTrade;
import org.powertac.common.Competition;
import org.powertac.common.DistributionTransaction;
import org.powertac.common.MarketPosition;
import org.powertac.common.MarketTransaction;
import org.powertac.common.Order;
import org.powertac.common.Orderbook;
import org.powertac.common.OrderbookOrder;
import org.powertac.common.TariffTransaction;
import org.powertac.common.Timeslot;
import org.powertac.common.WeatherForecast;
import org.powertac.common.WeatherReport;
import org.powertac.common.msg.MarketBootstrapData;
import org.powertac.common.repo.TimeslotRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import edu.utexas.cs.tactex.ConfiguratorFactoryService.BidStrategy;
import edu.utexas.cs.tactex.interfaces.Activatable;
import edu.utexas.cs.tactex.interfaces.BalancingManager;
import edu.utexas.cs.tactex.interfaces.BrokerContext;
import edu.utexas.cs.tactex.interfaces.Initializable;
import edu.utexas.cs.tactex.interfaces.MarketManager;
import edu.utexas.cs.tactex.interfaces.PortfolioManager;
import edu.utexas.cs.tactex.utils.BrokerUtils;
import edu.utexas.cs.tactex.utils.BrokerUtils.PriceMwhPair;

/**
 * Handles market interactions on behalf of the master.
 * @author John Collins
 */
@Service
public class MarketManagerService 
implements MarketManager, Initializable, Activatable
{
  static private Logger log = Logger.getLogger(MarketManagerService.class);
  
  private BrokerContext brokerContext; // master
  
  @Autowired
  private TimeslotRepo timeslotRepo;
  
  @Autowired
  private PortfolioManager portfolioManager;

  @Autowired
  private BalancingManager balancingManager;
  
  @Autowired  
  private ConfiguratorFactoryService configuratorFactoryService;

  // local state
  private Random randomGen = new Random();
  
  // max and min offer prices. Max means "sure to trade"
  private double BUY_LIMIT_PRICE_MAX = -1.0;  // broker pays
  private double BUY_LIMIT_PRICE_MIN = -70.0;  // broker pays
  private double SELL_LIMIT_PRICE_MAX = 70.0;    // other broker pays
  private double SELL_LIMIT_PRICE_MIN = 0.5;    // other broker pays
  private double MIN_MWH = 0.001; // minimum market order - updated with Comp. msg

  private double bidEpsilon = 0.001; // 0.1 cent to be above clearing price


  // ///////////////////////////////////////////////////
  // FIELDS THAT NEED TO BE INITIALIZED IN initialize()
  // EACH FIELD SHOULD BE ADDED TO test_initialize() 
  // ///////////////////////////////////////////////////

  private double marketTotalMwh;
  private double marketTotalPayments;

  // Bid recording
  private HashMap<Integer, Order> lastOrder;
  private double[] marketMWh;
  private double[] marketPayments;

  // record usage predictions: diff \in [1,24] => (future-ts => usage)
  private double[][] predictedUsage;
  private double[] actualUsage;

  private HashMap<Integer, Orderbook> orderbooks;

  private double maxTradePrice;
  private double minTradePrice;

  private TreeMap<Integer,ArrayList<PriceMwhPair>> supportingBidGroups;

  private DPCache dpCache2013;

  private ArrayList<ChargeMwhPair> shortBalanceTransactionsData;
  private ArrayList<ChargeMwhPair> surplusBalanceTransactionsData;



  public MarketManagerService ()
  {
    super();
  }


  /* (non-Javadoc)
   * @see edu.utexas.cs.tactex.MarketManager#init(edu.utexas.cs.tactex.SampleBroker)
   */
  @SuppressWarnings("unchecked")
  @Override
  public void initialize (BrokerContext brokerContext)
  {
    
    
    // NEVER CALL ANY SERVICE METHOD FROM HERE, SINCE THEY ARE NOT GUARANTEED
    // TO BE initalize()'d. 
    // Exception: it is OK to call configuratorFactory's public
    // (application-wide) constants



    this.brokerContext = brokerContext;
    marketTotalMwh = 0;
    marketTotalPayments = 0;
    lastOrder = new HashMap<Integer, Order>();
    marketMWh = new double[configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH()];
    Arrays.fill(marketMWh, 1e-9); // to avoid 0-division
    marketPayments = new double[configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH()];
    predictedUsage = new double[24][2500];
    actualUsage = new double[2500];
    orderbooks = new HashMap<Integer, Orderbook>();
    maxTradePrice = -Double.MAX_VALUE;
    minTradePrice = Double.MAX_VALUE;
    supportingBidGroups = new TreeMap<Integer, ArrayList<PriceMwhPair>>();
    dpCache2013 = new DPCache();
    shortBalanceTransactionsData = new ArrayList<ChargeMwhPair>();
    surplusBalanceTransactionsData = new ArrayList<ChargeMwhPair>();
  }

  
  // --------------- message handling -----------------

  /**
   * Handles the Competition instance that arrives at beginning of game.
   * Here we capture minimum order size to avoid running into the limit
   * and generating unhelpful error messages.
   */
  public synchronized void handleMessage (Competition comp)
  {
    MIN_MWH = Math.max(MIN_MWH, comp.getMinimumOrderQuantity());
    log.info("MIN_MWH is " + MIN_MWH);
  }


  /**
   * Handles a BalancingTransaction message.
   */
  public synchronized void handleMessage (BalancingTransaction tx)
  {
    log.info("Balancing tx: " + tx.getCharge());
    if (tx.getKWh() < 0) {
      double mwhSuppliedToMe = -tx.getKWh() / 1000.0;
      shortBalanceTransactionsData.add(new ChargeMwhPair(tx.getCharge(), mwhSuppliedToMe));
    }
    else {
      log.debug(" should support positive transactions - am I doint it right?");
      double mwhReturnedToMarket = -tx.getKWh() / 1000.0;
      surplusBalanceTransactionsData.add(new ChargeMwhPair(tx.getCharge(), mwhReturnedToMarket));
    }
    if (configuratorFactoryService.isUseBal()) {
      log.error("updating balancing tx");
      int timeslot = tx.getPostedTimeslotIndex();
      double mwh = -(tx.getKWh() / 1000.0); // '-' since sign is my surplus
      double price = tx.getCharge() / Math.abs(mwh);
      updateMarketTracking(timeslot, mwh, price);
    }
  }


  /**
   * Handles a ClearedTrade message - this is where you would want to keep
   * track of market prices.
   */
  public synchronized void handleMessage (ClearedTrade ct)
  {
    int timeslot = ct.getTimeslotIndex();
    double mwh = ct.getExecutionMWh();
    double price = ct.getExecutionPrice();
    int tradeCreationTimeslot = timeslotRepo.getTimeslotIndex(ct.getDateExecuted());
    if (false == configuratorFactoryService.isUseMtx()) {
    	updateMarketTracking(timeslot, mwh, price);
    }
    updateLowHighTradePrices(price);
    recordTradeResult(tradeCreationTimeslot, timeslot, price, mwh);    
  }


  /**
   * Handles a DistributionTransaction - charges for transporting power
   */
  public synchronized void handleMessage (DistributionTransaction dt)
  {
    log.info("Distribution tx: " + dt.getCharge());
  }


  /**
   * Receives a MarketBootstrapData message, reporting usage and prices
   * for the bootstrap period. We record the overall weighted mean price,
   * as well as the mean price and usage for a week.
   */
  public synchronized void handleMessage (MarketBootstrapData data)
  {
    int discardedTimeslots = Competition.currentCompetition().getBootstrapDiscardedTimeslots(); 
    for (int i = 0; i < data.getMwh().length; i++) {
      double mwh = data.getMwh()[i];
      double price = Math.abs(data.getMarketPrice()[i]); // we record market prices as positive
      int timeslot = i + discardedTimeslots;
      updateMarketTracking(timeslot, mwh, price);
    }
    
    // set limits
    double avgMktPrice = Math.abs(getMeanMarketPricePerMWH());
    BUY_LIMIT_PRICE_MIN = -3 * avgMktPrice;  
    SELL_LIMIT_PRICE_MAX = 3 * avgMktPrice;  
    log.info(" mk BUY_LIMIT_PRICE_MIN " + BUY_LIMIT_PRICE_MIN + " SELL_LIMIT_PRICE_MAX " + SELL_LIMIT_PRICE_MAX);
    
    // seed balancing with artificial, low-weight data, just so it's not empty
    double smallamount = 0.001; // 1 kwh
    double meanMktPricePerKwh = getMeanMarketPricePerKWH();
    double sigma = getMarketPricePerKWHRecordStd();
    double highMarketPricePerKwh =  meanMktPricePerKwh + 2 * sigma;
    double lowMarketPricePerKwh  =  meanMktPricePerKwh - 2 * sigma;
    double highMarketPricePerMwh = highMarketPricePerKwh * 1000;
    double lowMarketPricePerMwh  = lowMarketPricePerKwh  * 1000;
    shortBalanceTransactionsData.add(new ChargeMwhPair(-highMarketPricePerMwh * smallamount, smallamount));
    surplusBalanceTransactionsData.add(new ChargeMwhPair(lowMarketPricePerMwh * smallamount, -smallamount));
  }


  /**
   * Receives a MarketPosition message, representing our commitments on 
   * the wholesale market
   */
  public synchronized void handleMessage (MarketPosition posn)
  {
    brokerContext.getBroker().addMarketPosition(posn, posn.getTimeslotIndex());
  }
  

  /**
   * Receives a new MarketTransaction. We look to see whether an order we
   * have placed has cleared.
   */
  public synchronized void handleMessage (MarketTransaction tx)
  {
    // reset price escalation when a trade fully clears.
    Order lastTry = lastOrder.get(tx.getTimeslotIndex());
    if (lastTry == null) // should not happen
      //log.error("order corresponding to market tx " + tx + " is null");
      ;
    else if (tx.getMWh() == lastTry.getMWh()) // fully cleared
      lastOrder.put(tx.getTimeslotIndex(), null);

    if (true == configuratorFactoryService.isUseMtx()) {
      updateMarketTracking(tx.getTimeslotIndex(), Math.abs(tx.getMWh()), Math.abs(tx.getPrice()));
    }
  }

  
  /**
   * Receives the market orderbooks
   */
  public synchronized void handleMessage (Orderbook orderbook)
  {
    orderbooks.put(orderbook.getTimeslotIndex(), orderbook);
  }
  

  /**
   * Receives a new WeatherForecast.
   */
  public synchronized void handleMessage (WeatherForecast forecast)
  {
  }


  /**
   * Receives a new WeatherReport.
   */
  public synchronized void handleMessage (WeatherReport report)
  {
  }

  
  public synchronized void handleMessage (TariffTransaction ttx) {    
    // only interested in PRODUCE/CONSUME
    TariffTransaction.Type txType = ttx.getTxType();
    if( (TariffTransaction.Type.CONSUME == txType ||
         TariffTransaction.Type.PRODUCE == txType) 
        &&
        ttx.getBroker().getUsername().equals(brokerContext.getBrokerUsername())) {
      
      double kwh = ttx.getKWh();
      int postedTimeslotIndex = ttx.getPostedTimeslotIndex();
      double oldKwh = actualUsage[postedTimeslotIndex];        
      actualUsage[postedTimeslotIndex] = oldKwh + kwh;        

    }
  }


  // ----------- per-timeslot activation ---------------
  
  /* (non-Javadoc)
   * @see edu.utexas.cs.tactex.MarketManager#activate()
   */
  @Override
  public synchronized void activate (int currentTimeslotIndex)
  {
    try {

      log.info("activate, ts " + currentTimeslotIndex);

      List<Timeslot> enabledTimeslots = timeslotRepo.enabledTimeslots();

      try {

        // cleanup
        cleanOrderBooks(enabledTimeslots);

      } catch (Exception e) {
        log.error("caught exception from cleanOrderBooks(): ", e);
        //e.printStackTrace();        
      }

      try {

        // this code fragment is just for debugging
        int prevTimeslot = currentTimeslotIndex - 1;    
        String errors = " ee " + prevTimeslot + " a: " + String.format("%.2f", actualUsage[prevTimeslot]) + " p: ";
        for (double[] p : predictedUsage) {  
          errors += String.format("%.2f", p[prevTimeslot]) + " ";
        }
        log.info(errors);

      } catch (Exception e) {
        log.error("caught exception while printing errors: ", e);
        //e.printStackTrace();        
      }

      double neededKWh = 0.0;
      TreeMap<Integer, Double> dayAheadPredictions = new TreeMap<Integer, Double>();


      //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
      //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
      //
      // LATTE: 
      // The following for loop encapsulates lines 28-31 of the LATTE
      // algorithm.
      //
      // Note: LATTE's subroutine names do not directly correspond
      // to function names in this code.
      //
      //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
      //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ 
      //
      for (Timeslot timeslot : enabledTimeslots) {

        try {

          int targetTimeslot = timeslot.getSerialNumber();
          int index = targetTimeslot % configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH();
          if (configuratorFactoryService.isUseShiftPredMkt()) {
            neededKWh = portfolioManager.collectShiftedUsage(targetTimeslot, currentTimeslotIndex);
          }
          else {
            neededKWh = portfolioManager.collectUsage(index);
          }
          
          // balancing manager interaction
          if (configuratorFactoryService.isUseFudge()) {
            if (targetTimeslot == currentTimeslotIndex + 1) {
              balancingManager.updateFinalPrediction(targetTimeslot, -neededKWh);
            }
            neededKWh += balancingManager.getFudgeCorrection(currentTimeslotIndex);
          }

          submitOrder(neededKWh, targetTimeslot, currentTimeslotIndex, enabledTimeslots);

          dayAheadPredictions.put(targetTimeslot, neededKWh);

          recordTotalUsagePrediction(-neededKWh, targetTimeslot, currentTimeslotIndex);

        } catch (Exception e) {
          log.error("caught exception while submitting market orders: ", e);
          //e.printStackTrace();
        }
      } 

      log.info("done-activate"); 

    } catch (Throwable e) {
      log.error("caught exception from activate: ", e);
      //e.printStackTrace();
    }
  }


  // ----------------- data access and other subroutines -------------------
  /**
   * Returns the mean price observed in the market, per MWH
   */
  @Override
  public double getMeanMarketPricePerMWH ()
  {
    if (marketTotalMwh == 0) {
      log.error("marketTotalMwh should not be 0");
      return 0;
    }
    log.info("getMeanMarketPricePerMWH() " + marketTotalPayments / marketTotalMwh); 
    return marketTotalPayments / marketTotalMwh;
  }


  /**
   * Returns the mean price observed in the market, per KWH
   */
  @Override
  public double getMeanMarketPricePerKWH ()
  {
    return getMeanMarketPricePerMWH() / 1000.0;
  }


  @Override
  public double getMarketAvgPricePerSlotKWH(int timeslot) {
    int index = timeslot % configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH();
    double totalKWH = marketMWh[index] * 1000;
    double totalPayments = marketPayments[index];
    if (totalKWH == 0)
      totalKWH = 1e-9;
    return totalPayments / totalKWH; 
  }
  

  @Override
  public double getMarketPricePerKWHRecordStd() {
    ArrayRealVector marketAvgPricePerSlot = getMarketAvgPricesArrayKwh();    
    return Math.sqrt(StatUtils.variance(marketAvgPricePerSlot.toArray()));
  }


  @Override
  public ArrayRealVector getMarketAvgPricesArrayKwh() {
    RealVector marketKWh_ = new ArrayRealVector(marketMWh).mapMultiplyToSelf(1000.0).mapAddToSelf(1e-9); // avoid 0-div 
    ArrayRealVector marketPayments_ = new ArrayRealVector(marketPayments);
    // element by element division
    ArrayRealVector marketAvgPricePerSlot = marketPayments_.ebeDivide(marketKWh_);
    return marketAvgPricePerSlot;
  }  


  void recordTradeResult(int tradeCreationTimeslot, int timeslot,
      double price, double mwh) {
    int index = computeBidGroupIndex(tradeCreationTimeslot, timeslot);
    double bidPrice = price; // note recording a positive number, while bids are negative
    addTradeToGroup(index, bidPrice, mwh);
    log.info(" tg [" + index + "]" + tradeCreationTimeslot + "=>" + timeslot + " p " + price + " mwh " + mwh);
  }


  /**
   * @param index
   * @param bidPrice
   * @param mwh
   */
  private void addTradeToGroup(int index, double bidPrice, double mwh) {
    ArrayList<PriceMwhPair> bidGroup = getBidGroup(index);
    PriceMwhPair trade = new PriceMwhPair(bidPrice, mwh);
    // if trade exists in group, merge
    boolean exists = false;
    for (PriceMwhPair item : bidGroup) {
      if (item.getPricePerMwh() == trade.getPricePerMwh()) {
        item.addMwh(trade.getMwh());
        exists = true;
        break;
      }
    } 
    // otherwise, add sorted
    if ( ! exists ) {
      BrokerUtils.insertToSortedArrayList(bidGroup, trade);
    }
  }


  private ArrayList<PriceMwhPair> getBidGroup(int bidGroupIndex) {
    ArrayList<PriceMwhPair> group = supportingBidGroups.get(bidGroupIndex);
    if (null == group) {
      group = new ArrayList<PriceMwhPair>();
      supportingBidGroups.put(bidGroupIndex, group);
    }
    return group;
  }


  /**
   * Compute an trade index, between 1 to 24 (for n+1,...,n+24)
   * Note: the auction, and therfore the trade creation, take place
   * in the timeslot following the timeslot during which bids are 
   * submitted.
   * 
   * @param tradeCreationTimeslot
   * @param timeslot
   * @return
   */
  private int computeBidGroupIndex(int tradeCreationTimeslot, int timeslot) {    
    int bidsSubmisionTimeslot = tradeCreationTimeslot - 1;
    return timeslot - bidsSubmisionTimeslot;
  }


  void updateLowHighTradePrices(double price) {
    if (price > maxTradePrice) {
      maxTradePrice = price;
    }
    if (price < minTradePrice) {
      minTradePrice = price;
    }
  }
  /**
   * @param timeslot
   * @param mwh
   * @param price
   */
  void updateMarketTracking(int timeslot, double mwh, double price) {
    int index = indexIntoMarketRecord(timeslot);
    
    marketMWh[index] += mwh;
        // =
        //(marketMWh[index] * pass + mwh) / (pass + 1);
        //marketMWh[index] * (1 - alpha) + mwh * alpha;
    marketPayments[index] += price * mwh;
        // =
        //(marketPayments[index] * pass + price) / (pass + 1);
        //marketPayments[index] * (1 - alpha) + price * alpha;
        
    // add amounts to the total sums
    marketTotalMwh += mwh;
    marketTotalPayments += price * mwh;
  }


  /**
   * @param timeslot
   * @return
   */
  private int indexIntoMarketRecord(int timeslot) {
    // since usage record starts with the first bootstrap data point, which is
    // the next timeslot after bootstrapDiscardedTimeslots, every
    // timeslot-based index into the usage record should first subtract
    // bootstrapDiscardedTimeslots
    // e.g. should be timeslot - bootstrapDiscardedTimeslots here, if want 360
    // to map to index 0
    final int bootstrapDiscardedTimeslots = 
        Competition.currentCompetition().getBootstrapDiscardedTimeslots();
    return (timeslot - bootstrapDiscardedTimeslots) % 
        configuratorFactoryService.CONSTANTS.USAGE_RECORD_LENGTH();
  }


  /**
   * @param neededKWh
   * @param futureTimeslot
   * @param currentTimeslotIndex 
   */
  private void recordTotalUsagePrediction(double neededKWh, int futureTimeslot, int currentTimeslotIndex) {
    int predictionsIndex = indexToPredictionsArray(futureTimeslot, currentTimeslotIndex);
    log.info("trying [" + predictionsIndex + "][" + futureTimeslot + "]");
    predictedUsage[predictionsIndex][futureTimeslot] = neededKWh;
  }


  /**
   * @param futureTimeslot
   * @param currentTimeslotIndex 
   * @return
   */
  private int indexToPredictionsArray(int futureTimeslot, int currentTimeslotIndex) {
    return futureTimeslot - currentTimeslotIndex - 1; // -1 shifts from 1,24 => 0,23
  }


  /**
   * Access level is just because of testing
   * 
   * @param neededKWh
   * @param timeslot
   * @param currentTimeslotIndex 
   * @param enabledTimeslots 
   */
  protected void submitOrder (double neededKWh, int targetTimeslot, int currentTimeslotIndex, List<Timeslot> enabledTimeslots)
  {
    double neededMWh = neededKWh / 1000.0;
    
    log.debug("needed mwh=" + neededMWh +
              ", targetTimeslot " + targetTimeslot); 
    MarketPosition posn =
        brokerContext.getBroker().findMarketPositionByTimeslot(targetTimeslot);
    if (posn != null)
      neededMWh -= posn.getOverallBalance();
    log.debug("considering mkt-pos, needed mwh=" + neededMWh +
              ", targetTimeslot " + targetTimeslot);
    if (Math.abs(neededMWh) <= MIN_MWH) {
      log.info("no power required in targetTimeslot " + targetTimeslot);
      return;
    }


    List<Order> orders; 
    if (neededMWh > 0) {

      // heuristic reduction, to reduce potential surplus that needs to be
      // resold
      int timeToEnd = targetTimeslot - currentTimeslotIndex; 
      if (timeToEnd > 6) 
        neededMWh *= 0.8; 

      switch (configuratorFactoryService.getBidStrategy()) {

        case BASE:
          log.info("BASE");
          orders = baselineBidding(targetTimeslot, currentTimeslotIndex, neededMWh); 
          break;
         
         
        case MKT:
          log.info("MKT");
          orders = marketBidding(targetTimeslot, neededMWh);
          break;


        case DP13:
        default:
          log.info("DP13");
          if (canRunDP(currentTimeslotIndex, enabledTimeslots)) {
            List<Order> nonAdjustedOrders = dpBasedLimit2013(targetTimeslot, neededMWh, currentTimeslotIndex);      
            orders = addMarginToOrders(currentTimeslotIndex, nonAdjustedOrders);
          }
          else {
            orders = configuratorFactoryService.isUseStairBidExplore() ?
              explorationStairsBidding(targetTimeslot, currentTimeslotIndex, neededMWh) :
              explorationNullBalancingBidding(targetTimeslot, currentTimeslotIndex, neededMWh);
          }
          break;

      }
    }
    else {
      //orders = baselineBidding(targetTimeslot, currentTimeslotIndex, neededMWh);
      orders = balancingLimitsSellBidding(targetTimeslot, currentTimeslotIndex, neededMWh);
      //orders = balancingBasedBidding(targetTimeslot, currentTimeslotIndex, neededMWh);
      //orders = null;
    }

    if (orders != null) {
      sendMarketOrders(targetTimeslot, orders);
    }
  }


  private List<Order> explorationStairsBidding(int targetTimeslot,
      int currentTimeslotIndex, double neededMWh) {

    List<Order> orders = new ArrayList<Order>();

    boolean isBuying = neededMWh > 0; 
    if ( ! isBuying ) {
      log.error("explorationStairsBidding assumes buying. Returning empty list of orders");
      return orders;
    }

    List<Double> limitPrices = new ArrayList<Double>();

    double balancingLimit = Math.abs(meanOfBalancingPrices(shortBalanceTransactionsData));
    double avgMktPrice = Math.abs(getMeanMarketPricePerMWH());
    double upperBid = Math.min(-2 * avgMktPrice, -balancingLimit); // the higher of 2xAvg-Mkt and Avg-shortBalancing
    log.debug("explorationStairsBidding(): balancingLimit " + balancingLimit + " avgMktPrice " + avgMktPrice + " upperBid " + upperBid);
    double lowerBid = -1.0; // -15.23859038493; // i.e. -15
    final double numBids = 18;
    final double delta = (upperBid - lowerBid) / numBids;
    for (double limit = lowerBid; limit > upperBid; limit += delta) {
      log.debug("explorationStairsBidding() limit=" + limit);
      limitPrices.add(limit + 0.5 * delta * randomGen.nextDouble()); // add random element
    } 
    // add small bids
    for (Double limit : limitPrices) {
      orders.add(new Order(brokerContext.getBroker(), targetTimeslot, MIN_MWH, limit));
    }
    // add balancing-based bid
    orders.add(new Order(brokerContext.getBroker(), targetTimeslot, neededMWh, upperBid));
    
    return orders;
  }


  private List<Order> explorationNullBalancingBidding(int targetTimeslot,
      int currentTimeslotIndex, double neededMWh) {
    List<Order> orders;
    // new method: small mkt order if has time, balancing-based limit otherwise
    if (targetTimeslot == currentTimeslotIndex + 1) { 
      orders = balancingBasedBidding(targetTimeslot, currentTimeslotIndex, neededMWh);
    }
    else { 
      orders = new ArrayList<Order>();
      orders.add(new Order(brokerContext.getBroker(), targetTimeslot, MIN_MWH, null));
    } 
    return orders;
  }


  private List<Order> marketBidding(int targetTimeslot, double neededMWh) {	
    List<Order> resultingOrders = new ArrayList<Order>();
    Order order = new Order(brokerContext.getBroker(), targetTimeslot, neededMWh, null);
    resultingOrders.add(order);
    return resultingOrders; 
  }


  private List<Order> balancingBasedBidding(int targetTimeslot,
      int currentTimeslotIndex, double neededMWh) {
    
    double limit;
    if (neededMWh > 0) { // buy
      limit = meanOfBalancingPrices(shortBalanceTransactionsData);
    } else { // sell
      limit = meanOfBalancingPrices(surplusBalanceTransactionsData);
    }
    
    log.info("balancing-based bid: neededMwh=" + neededMWh + " limit=" + limit);
    
    List<Order> resultingOrders = new ArrayList<Order>();
    Order order = new Order(brokerContext.getBroker(), targetTimeslot, neededMWh, limit);
    resultingOrders.add(order);
    return resultingOrders;
  }


  private List<Order> balancingLimitsSellBidding(int targetTimeslot,
      int currentTimeslotIndex, double neededMwh) {

    if (neededMwh > 0) {
      // shouldn't happen
      log.error("balancingLimitedSellBidding(): How come neededMwh > 0 when trying to sell?");
      return new ArrayList<Order>(); // empty list
    }

    log.info("balancingLimitedSellBidding(), neededMwh = " + neededMwh);

    List<Order> resultingOrders = new ArrayList<Order>();
    
    // typically should be negative:
    double buyBalancePrice = meanOfBalancingPrices(shortBalanceTransactionsData);
    // typically should be positive:
    double sellBalancingPrice = meanOfBalancingPrices(surplusBalanceTransactionsData);
    log.info("buyBalancePrice = " + buyBalancePrice + " sellBalancingPrice = " + sellBalancingPrice);

    // worst I am willing to sell for is sell-balancing-price (or 0, if < 0)
    double willingToSellLimit = Math.max(0, sellBalancingPrice); 
    // best I expect opponent to pay is his balancing price, (assumed to be like mine)
    double tryingToSellLimit = -buyBalancePrice; // '-' inverts from buy-price to sell-price
    log.info("willingToSellLimit = " + willingToSellLimit + " tryingToSellLimit " + tryingToSellLimit);

    if (tryingToSellLimit > willingToSellLimit) {
      int remainingTries = (targetTimeslot - 1) - currentTimeslotIndex;
      double priceStep = (tryingToSellLimit - willingToSellLimit) / 24.0;
      log.info("remainingTries = " + remainingTries + " priceStep = " + priceStep);

      // METHOD 1 (could be exploitable)
      double limit = willingToSellLimit + priceStep * remainingTries;
      Order order = new Order(brokerContext.getBroker(), targetTimeslot, neededMwh, limit);
      resultingOrders.add(order); 
      log.info("order: mwh = " + neededMwh + " limit = " + limit);

      // METHOD 2
      //double mwhLeft = neededMwh;
      //// create step orders
      //for (int i = remainingTries; (i > 0) && (mwhLeft < 0); --i) {
      //  double stepMwh = -MIN_MWH;
      //  double limit = willingToSellLimit + priceStep * i;
      //  Order order = new Order(brokerContext.getBroker(), targetTimeslot, stepMwh, limit);
      //  resultingOrders.add(order); 
      //  log.info("order: mwh = " + stepMwh + " limit = " + limit);
      //  mwhLeft -= stepMwh;
      //}
      //// create order for the rest of the amount
      //if (mwhLeft < 0) {
      //  Order order = new Order(brokerContext.getBroker(), targetTimeslot, mwhLeft, willingToSellLimit);
      //  resultingOrders.add(order); 
      //  log.info("order: mwh = " + mwhLeft + " limit = " + willingToSellLimit);
      //}
    }
    else { // better to wait for balancing
      log.info("no steps, balancing sell price");
      Order order = new Order(brokerContext.getBroker(), targetTimeslot, neededMwh, willingToSellLimit);
      resultingOrders.add(order); 
    }
    
    return resultingOrders;
  }

  
  private List<Order> baselineBidding(int targetTimeslot, int currentTimeslotIndex,
      double neededMWh) {
    // otherwise - use the old way
    List<Order> resultingOrders = new ArrayList<Order>();
    Double limitPrice = baselineStrategyLimitPrice(targetTimeslot, neededMWh, currentTimeslotIndex);
    log.info(" dp computeLimitPrice() " + limitPrice );
    Order order = new Order(brokerContext.getBroker(), targetTimeslot, neededMWh, limitPrice);
    resultingOrders.add(order);
    return resultingOrders;
  }


  private List<Order> addMarginToOrders(int currentTimeslotIndex,
      List<Order> nonAdjustedOrders) {
    List<Order> resultingOrders = new ArrayList<Order>();
    // make sure not null
    if (nonAdjustedOrders == null || resultingOrders == null) {
      log.warn("addMarginToOrders(): orders cannot be null, bouncing input to calling function");
      resultingOrders = nonAdjustedOrders;
    }
    resultingOrders.clear();
    for (Order o: nonAdjustedOrders) {
      log.info("adjusting limit, ts " + currentTimeslotIndex);
      Double limitPrice = o.getLimitPrice();
      log.info("limit before " + limitPrice);
      if (limitPrice != null) { // not a mkt order
        limitPrice -= configuratorFactoryService.getBidMgn();
      }
      log.info("limit after " + limitPrice);
      resultingOrders.add(new Order(brokerContext.getBroker(), o.getTimeslotIndex(), o.getMWh(), limitPrice));
    }
    //}
    return resultingOrders;
  }


  private void sendMarketOrders(int targetTimeslot, List<Order> orders) {
    for (Order o : orders) {
      log.info("new order for " + o.getMWh() + " at " + o.getLimitPrice() +
               " in targetTimeslot " + o.getTimeslotIndex());
      lastOrder.put(targetTimeslot, o);
      brokerContext.sendMessage(o);
    }
  }


  /**
   * Computes a limit price with a random element. 
   * @param currentTimeslotIndex 
   */
  private Double baselineStrategyLimitPrice (int timeslot,
                                    double neededMwh, int currentTimeslotIndex)
  {
    log.info(" mk computeLimitPrice(" + neededMwh +") ");
    
    
    int current = currentTimeslotIndex;
    int remainingTries = (timeslot - current
                          - Competition.currentCompetition().getDeactivateTimeslotsAhead());

    
    boolean isBuying = neededMwh > 0.0;
    SortedSet<OrderbookOrder> 
        outstandingOrders = 
            getOutstandingOrdersIfExist(timeslot, isBuying);
    // TODO temporary code, as long as they could be null
    // later they should be of length 0
    double upperlimit = 
      outstandingOrders != null ? 
        bestPossiblePrice(neededMwh, outstandingOrders)
        :
        orderIndependentUpperLimit(isBuying);    

      log.info(" mk bestPossiblePrice Limit:" + upperlimit); 
      
    return origLimitComputation(timeslot, neededMwh, upperlimit, currentTimeslotIndex);
  }


  /**
   * @param timeslot
   * @param isBuying
   * @return
   */
  private SortedSet<OrderbookOrder> getOutstandingOrdersIfExist(int timeslot,
      boolean isBuying) {
    Orderbook o = orderbooks.get(timeslot);
    if ( o == null) {
      return null;    
    }
    else {   
      // if buying, we want other asks, if selling we want other bids
      return isBuying ? o.getAsks() : o.getBids();  
    }    
  }


  /**
   * Make sure we have a large enough sample of historic 
   * clearing prices, and balancingTx for each future auction
   * @param currentTimeslotIndex 
   * @param enabledTimeslots 
   * 
   * @return
   */
  boolean canRunDP(int currentTimeslotIndex, List<Timeslot> enabledTimeslots) {
    // check 24 points in each of the previous slots 1..i
    int largeEnoughSample = configuratorFactoryService.CONSTANTS.LARGE_ENOUGH_SAMPLE_FOR_MARKET_TRADES();
    
     // should normally be 24
    if (supportingBidGroups.size() < enabledTimeslots.size()) 
      return false;

    // these are considered 'step 0' meaning letting the imbalance
    // be resolved by the DU
    if (shortBalanceTransactionsData.size() < largeEnoughSample)
      return false;

    log.debug("assuming a trade is created the following timeslot of a bid");
    int nextTradeCreationTimeslot = currentTimeslotIndex + 1;
    for (Timeslot timeslot : enabledTimeslots) {      
      int index = 
          computeBidGroupIndex(nextTradeCreationTimeslot, 
              timeslot.getSerialNumber());  
      if (supportingBidGroups.get(index).size() < largeEnoughSample) {
        return false;
      }
    }
    return true;
  }


  private List<Order> dpBasedLimit2014(int targetTimeslot, double neededMwh, int currentTimeslotIndex) {
    DPResult result = runDP2014(targetTimeslot, neededMwh, currentTimeslotIndex);    
    double lowerLimit = result.getBestActionWithMargin();
    double upperLimit = result.getNextStateValue();
    return createTwoMktOrdersFromLimits(targetTimeslot, neededMwh,
			lowerLimit, upperLimit);
  }


  /**
   * default visibility for testing purposes
   * 
   * @param neededMwh
   * @param currentTimeslot
   */
  DPResult runDP2014(int targetTimeslot, double neededMwh, int currentTimeslot) {
    boolean isBuying = neededMwh > 0.0;
    // remember that the "latest" auction is 1
    // and the earliest is 24
    log.debug("Assuming bids only - add support for asks");
    if ( ! isBuying ) {
      log.error("asks are not supported yet - behavior undefined...");
    }
    log.debug("Assuming the whole amount is going to be cleared in one trade - not using neededMwh");
    log.debug("assuming that bidding the 'trade' price helped you win => move to ask/bid");

    ArrayList<Double> stateValues = new ArrayList<Double>();
    ArrayList<Double> bestActions = new ArrayList<Double>();

    // TODO isBuying must be true currently - future extension might
    // support bids, and then the next lines would have to be revised
    // if there are outstanding asks, we will ignore all lower bid candidates
    //int targetTimeslot = currentTimeslot + index;
    SortedSet<OrderbookOrder> 
        outstandingOrders = 
            getOutstandingOrdersIfExist(targetTimeslot, isBuying); 
    double lowestAskPrice = 
        outstandingOrders != null ?
        lowestAsk(outstandingOrders) 
        : 0; 

    // step-0 value: any amount that was not purchased is balanced
    double valueOfStep0 = meanOfBalancingPrices(shortBalanceTransactionsData);
    // seed the DP algorithm
    stateValues.add(valueOfStep0); 
    bestActions.add(null); // actually, not Market order, but noop => balancing
    // log.info(" dp balancing estimation: " + nextStateValue);
    int currentMDPState = targetTimeslot - currentTimeslot;
    // DP back sweep
    //for (int index = 1; index <= supportingBidGroups.size(); ++index) {
    for (int index = 1; index <= currentMDPState; ++index) {
      ArrayList<PriceMwhPair> currentGroup = getBidGroup(index);
      double totalEnergyInCurrentGroup = sumTotalEnergy(currentGroup);

      // scan action values and choose the best

      // seed with no-op (bid 0) => value of next state
      int indexOfNextState = index - 1;
      double bestActionValue = stateValues.get(indexOfNextState);
      double bestPrice = -0.0;
      double acumulatedEnergy = 0;
      for (PriceMwhPair c : currentGroup) {
        if (c.getPricePerMwh() < lowestAskPrice) {
          // skip
          totalEnergyInCurrentGroup -= c.getMwh();// <= Is this a bayesian monty-hall? if yes - should improve!  
        }
        else {
          //// log.info(" dp i=" + i);
          acumulatedEnergy += c.getMwh();
          double Psuccess = acumulatedEnergy / totalEnergyInCurrentGroup;
          double Pfail = 1 - Psuccess;
          double bidPrice = -c.getPricePerMwh(); // trades are positive, bids are negative
          //// log.info(" dp action=" + bidPrice + " Psuccess=" + Psuccess);
          double nextStateValue = stateValues.get(indexOfNextState);
          double actionValue = Psuccess * bidPrice + Pfail * nextStateValue; 
          //// log.info(" dp actionValue=" + actionValue);
          if (actionValue > bestActionValue) { 
            bestActionValue = actionValue;
            bestPrice = bidPrice; 
            //// log.info(" dp update bestActionValue=" + bestActionValue);
            //// log.info(" dp update bestPrice=" + bidPrice);
          }        
        }
      }
      stateValues.add(bestActionValue);
      bestActions.add(bestPrice);
      //// log.info(" dp added bestPrice=" + bestPrice + " bestActionValue=" + bestActionValue);
    }     
    return new DPResult(currentMDPState, bestActions, stateValues);
  }


  private List<Order> dpBasedLimit2013(int targetTimeslot, double neededMwh, int currentTimeslotIndex) {

    if ( ! dpCache2013.isValid(currentTimeslotIndex)) {
      ////       log.info(" dp cache invalid: running DP, ts " + currentTimeslot + " n+i " + timeslot);
      runDP2013(neededMwh, currentTimeslotIndex);
    }

    log.debug("assuming a trade is created the following timeslot of a bid");
    int tradeCreationTimeslot = currentTimeslotIndex + 1;
    int bidGroupIndex = computeBidGroupIndex(tradeCreationTimeslot , targetTimeslot);    

    // Create the required number of stairs
    int numStairs = configuratorFactoryService.getNumStairs();
    if (numStairs == 24) {
      try {
        return dp2013CreateManyMktOrders(targetTimeslot, neededMwh,
				bidGroupIndex);
      } catch (Exception e) {	
        //e.printStackTrace();
        log.error("DP13 cannot generate many stairs, trying 2");
        return dp2013CreateTwoMktOrders(targetTimeslot, neededMwh,
				bidGroupIndex);
      }
    }
    else if (numStairs == 2) {
      return dp2013CreateTwoMktOrders(targetTimeslot, neededMwh, bidGroupIndex);
    }
    else {
      List<Order> orders = new ArrayList<Order>();
      double lowerLimit = dpCache2013.getBestActionWithMargin(bidGroupIndex);
      orders.add(new Order(brokerContext.getBroker(), targetTimeslot, neededMwh, lowerLimit));
      return orders;
    }
  }


  private List<Order> dp2013CreateManyMktOrders(int targetTimeslot,
      double neededMwh, int bidGroupIndex) {
    List<Double> limits = new ArrayList<Double>();
    // insert stairs from low to high:
    // 1. current bestActions
    // 2. next-state-value=>last-state-value
    limits.add(dpCache2013.getBestActionWithMargin(bidGroupIndex));
    for (int i = bidGroupIndex - 1; i >= 0; --i){
      limits.add(dpCache2013.getStateValues().get(i));
    }
    return createManyMktOrdersFromLimits(targetTimeslot, neededMwh, limits);
  }


  private List<Order> dp2013CreateTwoMktOrders(int targetTimeslot,
      double neededMwh, int bidGroupIndex) {
    double lowerLimit = dpCache2013.getBestActionWithMargin(bidGroupIndex);
    double upperLimit = dpCache2013.getStateValues().get(bidGroupIndex - 1);
    return createTwoMktOrdersFromLimits(targetTimeslot, neededMwh,
        lowerLimit, upperLimit);
  }


  private List<Order> createManyMktOrdersFromLimits(int targetTimeslot,
      double neededMwh, List<Double> limits) {
    List<Order> orders = new ArrayList<Order>();

    // add small stairs
    double mwhLeft = neededMwh;
    int i = 0; 
    for (    ; mwhLeft > 0 && i < limits.size() - 1; mwhLeft -= MIN_MWH, ++i) {
      double limit = limits.get(i);
      orders.add(new Order(brokerContext.getBroker(), targetTimeslot, MIN_MWH, limit));
    }

    // Add last, large stair if still mwh left; upperLimit is either last
    // state's value (balancing-price) or the value of the MDP state to which
    // we have reached if neededMwh is small (this is an edge case)
    double upperLimit = limits.get(i);
    if (mwhLeft >= MIN_MWH) {
      orders.add(new Order(brokerContext.getBroker(), targetTimeslot, mwhLeft, upperLimit));
    }
    return orders; 
  }


  private List<Order> createTwoMktOrdersFromLimits(int targetTimeslot,
      double neededMwh, double lowerLimit, double upperLimit) {
    List<Order> orders = new ArrayList<Order>();
    double mwhForUpperLimit = Math.max(neededMwh - MIN_MWH, MIN_MWH);
    double mwhForLowerLimit = MIN_MWH;
    orders.add(new Order(brokerContext.getBroker(), targetTimeslot, mwhForUpperLimit, upperLimit));
    orders.add(new Order(brokerContext.getBroker(), targetTimeslot, mwhForLowerLimit, lowerLimit));
    return orders;
  }


  /**
   * @param neededMwh
   * @param currentTimeslot
   */
  void runDP2013(double neededMwh, int currentTimeslot) {
    dpCache2013.clear();

    boolean isBuying = neededMwh > 0.0;
    // remember that the "latest" auction is 1
    // and the earliest is 24
    log.debug("Assuming bids only - add support for asks");
    if ( ! isBuying ) {
      log.error("asks are not supported yet! behavior undefined...");
    }
    log.debug("Assuming the whole amount is going to be cleared in one trade - not using neededMwh");
    log.debug("assuming that bidding the 'trade' price helped you win => move to ask/bid");

    ArrayList<Double> stateValues = dpCache2013.getStateValues();
    ArrayList<Double> bestActions = dpCache2013.getBestActions();

    // step-0 value: any amount that was not purchased is balanced
    double valueOfStep0 = meanOfBalancingPrices(shortBalanceTransactionsData);
    // if buys too well at start => underestimates balancing-costs,
    // so we add protection in the first week until there is enough 
    // data.
    if (configuratorFactoryService.isUseStairBidExplore() && currentTimeslot < 360 + 168) { 
      // only in the first week, protect against too low prices:
      // the higher of 2xAvg-Mkt and Avg-shortBalancing 
      double oldValueOfStep0 = valueOfStep0;
      double avgMktPrice = Math.abs(getMeanMarketPricePerMWH());
      valueOfStep0 = Math.min(-2 * avgMktPrice, valueOfStep0); 
      log.debug("DP: meanOfBalancingPrices " + oldValueOfStep0 + " avgMktPrice " + avgMktPrice + " valueOfStep0 " + valueOfStep0);
    }
    
    // seed the DP algorithm
    stateValues.add(valueOfStep0); 
    bestActions.add(null); // actually, not Market order, but noop => balancing
    // log.info(" dp balancing estimation: " + nextStateValue);
    // DP back sweep
    for (int index = 1; index <= supportingBidGroups.size(); ++index) {
      
      ArrayList<PriceMwhPair> currentGroup = getBidGroup(index);
      double totalEnergyInCurrentGroup = sumTotalEnergy(currentGroup);

      int targetTimeslot = currentTimeslot + index;
      SortedSet<OrderbookOrder> 
          outstandingOrders = 
              getOutstandingOrdersIfExist(targetTimeslot, isBuying); 
      double lowestAskPrice = 
          outstandingOrders != null ?
          lowestAsk(outstandingOrders) 
          : 0; 
      
      // scan action values and choose the best
      double bestActionValue = stateValues.get(stateValues.size() - 1);
      double bestPrice = -0.0;
      double acumulatedEnergy = 0;
      for (PriceMwhPair c : currentGroup) {
        if (c.getPricePerMwh() < lowestAskPrice) {
          totalEnergyInCurrentGroup -= c.getMwh();
        }
        else {
          acumulatedEnergy += c.getMwh();
          double Psuccess = acumulatedEnergy / totalEnergyInCurrentGroup;
          double Pfail = 1 - Psuccess;
          double bidPrice = -c.getPricePerMwh(); // trades are positive, bids are negative
          double nextStateValue = stateValues.get(stateValues.size() - 1);
          double actionValue = Psuccess * bidPrice + Pfail * nextStateValue; 
          if (actionValue > bestActionValue) { 
            bestActionValue = actionValue;
            bestPrice = bidPrice; 
          }        
        }
      }
      stateValues.add(bestActionValue);
      bestActions.add(bestPrice);
    }
    
    dpCache2013.setValid(currentTimeslot);
  }


  private double lowestAsk(SortedSet<OrderbookOrder> outstandingOrders) {
    if( null == outstandingOrders )
      return 0;
    double lowestAsk = Double.MAX_VALUE;
    for (OrderbookOrder ask : outstandingOrders) {
      if (ask != null) {
        lowestAsk = Math.min(lowestAsk, ask.getLimitPrice());            
      }
    }
    return lowestAsk < Double.MAX_VALUE ? lowestAsk : 0;
  }


  /**
   * @param currentGroup
   * @return
   */
  private double sumTotalEnergy(ArrayList<PriceMwhPair> currentGroup) {
    double totalEnergyInCurrentGroup = 0;
    for (PriceMwhPair c : currentGroup) {
      totalEnergyInCurrentGroup += c.getMwh();
    }
    return totalEnergyInCurrentGroup;
  }


  /**
   * @param supportingBidsGroup
   * @return
   */
  double meanOfBalancingPrices(ArrayList<ChargeMwhPair> balancingTxData) {
  
    int N = balancingTxData.size();
    if (N == 0) {
      log.error("shouldn't happen: meanOfBalancingPrices() should not be called with an empty ArrayList");
      return 0;
    }
    
    double totalMwh = 0;
    double totalCharge = 0;
    for (ChargeMwhPair c : balancingTxData) {
      totalCharge += c.getCharge();
      totalMwh += Math.abs(c.getMwh());
    }
    // shouldn't happen
    if (0 == totalMwh) {
      log.error("how come totalMwh in balancing is 0");
      return 0;
    }
    // normal case
    return (totalCharge / totalMwh);
  }


  /**
   * default visibility just for testing
   * 
   * @param supportingBidsGroup
   * @return
   */
  double meanClearingBidPrice(ArrayList<PriceMwhPair> supportingBidsGroup) {
    int N = supportingBidsGroup.size();
    if (N == 0) {
      log.error("shouldn't happen: meanClearingBidPrice() should not be called with an empty group");
      return BUY_LIMIT_PRICE_MIN;
    }
    double total = 0;
    for (PriceMwhPair c : supportingBidsGroup) {
      total += c.getPricePerMwh();
    }
    return total / N;
  }


  /**
   * @param timeslot
   * @param neededMwh
   * @param remainingTries
   * @param lowerLimit 
   * @param upperlimit 
   * @param currentTimeslotIndex 
   * @return
   */
   private Double origLimitComputation(int timeslot, double neededMwh,
       double upperlimit, int currentTimeslotIndex) {
  
    log.debug("Compute limit for " + neededMwh + 
        ", timeslot " + timeslot);
    // start with default limits
    double maxPrice;
    double minPrice;
    if (neededMwh > 0.0) {
      // buying
      maxPrice = BUY_LIMIT_PRICE_MAX;
      minPrice = BUY_LIMIT_PRICE_MIN;
    }
    else {
      // selling
      maxPrice = SELL_LIMIT_PRICE_MAX;
      minPrice = SELL_LIMIT_PRICE_MIN;
    }
    // check for escalation
    Order lastTry = lastOrder.get(timeslot);
    if (lastTry != null)
      log.debug("lastTry: " + lastTry.getMWh() +
          " at " + lastTry.getLimitPrice());
    if (lastTry != null
        && Math.signum(neededMwh) == Math.signum(lastTry.getMWh())) {
      Double tmp = lastTry.getLimitPrice();
      if (tmp != null) { // shouldn't happen.. but happened due to sync issues
        maxPrice = tmp;
        log.debug("old limit price: " + maxPrice);
      }
    }

    // set price between maxPrice and minPrice, according to number of
    // remaining chances we have to get what we need.
    int remainingTries = (timeslot - currentTimeslotIndex
                          - Competition.currentCompetition().getDeactivateTimeslotsAhead());
    log.debug("remainingTries: " + remainingTries);
    if (remainingTries > 0) {
      double range = (minPrice - maxPrice) * 2.0 / (double)remainingTries;
      log.debug("maxPrice=" + maxPrice + ", range=" + range);
      double computedPrice = maxPrice + randomGen.nextDouble() * range; 
      return Math.min(Math.max(minPrice, computedPrice), upperlimit);
    }
    else
      return null; // market order
  }


  /**
   * @param isBuying
   * @return
   */
  private double orderIndependentUpperLimit(boolean isBuying) {

    double globalLimit = isBuying ? BUY_LIMIT_PRICE_MAX : SELL_LIMIT_PRICE_MAX;
    //return globalLimit;
    double tradeBasedLimit = isBuying ? -Math.abs(minTradePrice) : Math.abs(maxTradePrice);

    if (validTradeBasedLimit(tradeBasedLimit, isBuying)){
      log.info(" mk tradeBasedLimit:" + tradeBasedLimit);
      return tradeBasedLimit;
    }
    else {
      log.info(" mk globalLimit:" + globalLimit);
      return globalLimit;
    }
  }


  private boolean validTradeBasedLimit(double tradeBasedLimit, boolean isBuying) {
    if (isBuying) {
      return BUY_LIMIT_PRICE_MIN < tradeBasedLimit && tradeBasedLimit < BUY_LIMIT_PRICE_MAX;
    }
    else {
      return SELL_LIMIT_PRICE_MIN < tradeBasedLimit && tradeBasedLimit < SELL_LIMIT_PRICE_MAX;
    }
  }


  /**
   * @param neededMwh
   * @param outstandingOrders
   * @return
   */
  private double bestPossiblePrice(double neededMwh,
      SortedSet<OrderbookOrder> outstandingOrders) {    
    double totalMwh = 0.0;
    double bestPossiblePrice = -Double.MAX_VALUE;      
    // Note: Assuming orders are sorted by price - this should be the case
    for (OrderbookOrder order : outstandingOrders) {        
      if (null == order) 
        continue;
      totalMwh += -order.getMWh(); // they sell, I buy        
      bestPossiblePrice = Math.max(bestPossiblePrice, order.getLimitPrice());
      log.info(" mk adding order mwh " + order.getMWh() + " limit " + order.getLimitPrice());
      if (Math.abs(totalMwh) > Math.abs(neededMwh)) {
        break;
      }
    }
    // inverting sign since minPossiblePrice is of the one who sells/buys to/from me
    // and reducing epsilon to make sure I could have cleared it      
    double limitPrice = -bestPossiblePrice - (0.00001 * Math.abs(bestPossiblePrice));
    return limitPrice;
  }


  private void cleanOrderBooks(List<Timeslot> enabledTimeslots) {
    
    for(Iterator<Entry<Integer, Orderbook>> it = orderbooks.entrySet().iterator(); it.hasNext(); ) {
      Entry<Integer, Orderbook> entry = it.next();      
      Integer timeslot = entry.getKey();
      if( ! timeslotIsEnabled(enabledTimeslots, timeslot)){    
        // meaning this timeslot is history
        it.remove();
      }
    }
  }


  /**
   * default access level for testing
   * 
   * @param enabledTimeslots
   * @param timeslotIndex
   * @return
   */
  boolean timeslotIsEnabled(List<Timeslot> enabledTimeslots,
      int timeslotIndex) {
    
    for (Timeslot t : enabledTimeslots) {
      if (t.getSerialNumber() == timeslotIndex) {
        return true;
      }
    }
    return false;    
  }


  /**
   * a pair holder for <charge, mwh>
   */
  public class ChargeMwhPair {
    
    double charge;
    double mwh;
    /**
     * @param charge
     * @param mwh
     */
    public ChargeMwhPair(double charge, double mwh) {
      super();
      this.charge = charge;
      this.mwh = mwh;
    }
    
    public double getCharge() {
      return charge;
    }
    
    public double getMwh() {
      return mwh;
    }
  }

  
  /**
   * holds the result of DP for current timeslot
   */
  class DPCache {
    private HashMap<Integer, Boolean> validTimeslots;

    private ArrayList<Double> stateValues;

    private ArrayList<Double> bestActions;

    public DPCache() {
      validTimeslots = new HashMap<Integer, Boolean>();
      stateValues = new ArrayList<Double>();
      bestActions = new ArrayList<Double>();
    }

    public void clear() {
      validTimeslots.clear();
      stateValues.clear();
      bestActions.clear();
    }

    public boolean isValid(int timeslot) {
      return getValidEntryFromMap(timeslot);
    }

    public void setValid(int timeslot) {
      validTimeslots.put(timeslot, true);      
    }

    
    public Double getBestAction(int bidGroupIndex) {
      return getBestActions().get(bidGroupIndex);
    }
    
    public double getBestActionWithMargin(int bidGroupIndex) {
    	return getBestAction(bidGroupIndex) - bidEpsilon; // '-' is correct for both bid/ask
    }
    
    public ArrayList<Double> getBestActions() {
      return bestActions;
    }

    public ArrayList<Double> getStateValues() {
      return stateValues;      
    }


    private boolean getValidEntryFromMap(int timeslot) {
      Boolean valid = validTimeslots.get(timeslot);
      if (null == valid) {
        valid = false;
        validTimeslots.put(timeslot, valid);
      }
      return valid;      
    }
  }


  class DPResult {
    private int currentMDPState;
    private ArrayList<Double> bestActions;
    private ArrayList<Double> stateValues;

    public DPResult(int currentMDPState, ArrayList<Double> bestActions,
        ArrayList<Double> stateValues) {
      this.currentMDPState = currentMDPState;
      this.bestActions = bestActions;
      this.stateValues = stateValues;
    }
    
    double getBestAction() {
      double bestAction = bestActions.get(currentMDPState);// + bidEpsilon;
      return bestAction;
    }
    
    double getBestActionWithMargin() {
      return getBestAction() - bidEpsilon; // '-' is correct for both bid/ask
    }

    double getNextStateValue() {
      return stateValues.get(currentMDPState - 1);
    }

    
    // the following are used for testing    
    ArrayList<Double> getStateValues() {		
      return stateValues;
    }

    ArrayList<Double> getBestActions() {
      return bestActions;
    }
  }
}
