package org.aprestos.labs.data.flume.sinks.influxdb;

import java.util.concurrent.TimeUnit;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.aprestos.labs.data.common.influxdb.PointDto;
import org.aprestos.labs.data.common.influxdb.PointUtils;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

public class InfluxDbSink extends AbstractSink implements Configurable {

	private static final Logger logger = LoggerFactory.getLogger(InfluxDbSink.class);
	private SinkCounter counter;
	private InfluxDB db;
	private int batchSize = Constants.DEFAULT_BATCH_SIZE;
	private long processedEvents = 0;

	@Override
	public Status process() throws EventDeliveryException {
		logger.info("process<IN>");
		
		Status result = Status.READY;
		Channel channel = getChannel();
		Transaction transaction = null;
		Event event = null;
		byte[] eventBody = null;
	
		try {
			transaction = channel.getTransaction();
			transaction.begin();
			while( null != ( event = channel.take() ) && null != (eventBody = event.getBody()) 
					&& 0 < eventBody.length && processedEvents < batchSize){
				PointDto point = PointUtils.fromBytes(eventBody);
				db.write(PointUtils.pointDto2Point(point));
				processedEvents++;
			}
			
			if (processedEvents == 0) {
				result = Status.BACKOFF;
				counter.incrementBatchEmptyCount();
			}
			else {
				counter.incrementBatchCompleteCount();
				logger.info("process | processed events: {}", processedEvents);
				processedEvents = 0;
			}
			transaction.commit();

		} catch (Exception ex) {
			String errorMsg = "Failed to write events to db, eventBody: {}";
			logger.error(errorMsg, null == eventBody ? "": new String(eventBody) , ex);
			result = Status.BACKOFF;
			if (transaction != null) {
				try {
					transaction.rollback();
				} catch (Exception e) {
					logger.error("Transaction rollback failed", e);
					throw Throwables.propagate(e);
				}
			}
			throw new EventDeliveryException(errorMsg, ex);
		} finally {
			if (transaction != null) 
				transaction.close();
		}
		logger.info("process<OUT>");
		return result;
	}

	@Override
	public synchronized void start() {
		logger.info("start<IN>");
		counter.start();
		super.start();
		logger.info("start<OUT>");
	}

	@Override
	public synchronized void stop() {
		logger.info("stop<IN>");
		if(null != db)
			db.close();
		counter.stop();
		logger.info("stopped. Metrics: {}", getName(), counter);
		super.stop();
		logger.info("stop<OUT>");
	}

	@Override
	public void configure(Context context) {
		logger.trace("configure<IN>");
		String host = null, port = null, user = null, pswd = null, dbName = null;
		Integer batchSize = null;
		try{
			if( null == ( host = context.getString(Constants.DB_HOST) ) )
				throw new IllegalStateException(String.format("!!! missing configuration: %s !!!", Constants.DB_HOST));
			if( null == ( port = context.getString(Constants.DB_PORT) ) )
				throw new IllegalStateException(String.format("!!! missing configuration: %s !!!", Constants.DB_PORT));
			if( null == ( user = context.getString(Constants.DB_USER) ) )
				throw new IllegalStateException(String.format("!!! missing configuration: %s !!!", Constants.DB_USER));
			if( null == ( pswd = context.getString(Constants.DB_PSWD) ) )
				throw new IllegalStateException(String.format("!!! missing configuration: %s !!!", Constants.DB_PSWD));
			if( null == ( dbName = context.getString(Constants.DB_NAME) ) )
				throw new IllegalStateException(String.format("!!! missing configuration: %s !!!", Constants.DB_NAME));
			if( null != ( batchSize = context.getInteger(Constants.BATCH_SIZE) ) )
				this.batchSize = batchSize;

			db = InfluxDBFactory.connect(String.format("http://%s:%s", host, port ), user, pswd);
			if(!db.databaseExists(dbName)) {
				db.createDatabase(dbName);
				logger.info(String.format("created database %s", dbName));
			}
			else
				logger.info(String.format("database %s exists!", dbName));
			
			db.setDatabase(dbName);
			db.setRetentionPolicy("autogen");

			// Flush every Points, at least every 100ms
			db.enableBatch(this.batchSize, 1000, TimeUnit.MILLISECONDS);
			
			counter = new SinkCounter(getName());
		}
		catch(Exception e){
			logger.error("configuration error", e);
		}
		logger.trace("configure<OUT>");
	}

}
