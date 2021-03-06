package org.owasp.appsensor.storage.influxdb;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.joda.time.DateTime;
import org.owasp.appsensor.core.Attack;
import org.owasp.appsensor.core.DetectionPoint;
import org.owasp.appsensor.core.Event;
import org.owasp.appsensor.core.User;
import org.owasp.appsensor.core.criteria.SearchCriteria;
import org.owasp.appsensor.core.logging.Loggable;
import org.owasp.appsensor.core.storage.AttackStore;
import org.owasp.appsensor.core.util.DateUtils;
import org.slf4j.Logger;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * Created by john.melton on 3/10/16.
 */
@Named
@Loggable
public class InfluxDbAttackStore extends AttackStore {

  private Logger logger;

  private InfluxDB influxDB;

  private final Gson gson = new Gson();

  private boolean initializedProperly = true;

  @Inject
  private Environment environment;

  /**
   * {@inheritDoc}
   */
  @Override
  public void addAttack(Attack attack) {
    logger.warn("Security attack " + attack.getDetectionPoint().getLabel() + " triggered by user: " + attack.getUser().getUsername());

    Point point = Point.measurement(Utils.ATTACKS)
        .time(DateUtils.fromString(attack.getTimestamp()).getMillis(), TimeUnit.MILLISECONDS)
        .field(Utils.LABEL, attack.getDetectionPoint().getLabel())
        .tag(Utils.USERNAME, attack.getUser().getUsername())
        .tag(Utils.TIMESTAMP, attack.getTimestamp())
        .tag(Utils.DETECTION_SYSTEM, attack.getDetectionSystem().getDetectionSystemId())
        .tag(Utils.CATEGORY, attack.getDetectionPoint().getCategory())
        .tag(Utils.LABEL, attack.getDetectionPoint().getLabel())
        .tag(Utils.THRESHOLD_COUNT, String.valueOf(attack.getDetectionPoint().getThreshold().getCount()))
        .tag(Utils.THRESHOLD_INTERVAL_DURATION, String.valueOf( attack.getDetectionPoint().getThreshold().getInterval().getDuration() ) )
        .tag(Utils.THRESHOLD_INTERVAL_UNIT, attack.getDetectionPoint().getThreshold().getInterval().getUnit())
        .field(Utils.JSON_CONTENT, gson.toJson(attack))
        .build();

    influxDB.write(Utils.DATABASE, "default", point);

    super.notifyListeners(attack);
  }

  @Override
  public Collection<Attack> findAttacks(SearchCriteria criteria) {
    Preconditions.checkNotNull(criteria, "criteria must be non-null");

    Collection<Attack> matches = new ArrayList<>();

    User user = criteria.getUser();
    DetectionPoint detectionPoint = criteria.getDetectionPoint();
    Collection<String> detectionSystemIds = criteria.getDetectionSystemIds();
    DateTime earliest = DateUtils.fromString(criteria.getEarliest());

    String influxQL = Utils.constructInfluxQL(Utils.ATTACKS, user, detectionPoint, detectionSystemIds, earliest, Utils.QueryMode.CONSIDER_DETECTION_POINT);

    Query query = new Query(influxQL, Utils.DATABASE);

    QueryResult results = influxDB.query(query);

    for(QueryResult.Result result : results.getResults()) {
      if(result == null || result.getSeries() == null) {
        continue;
      }

      for(QueryResult.Series series : result.getSeries()) {
        if(series == null || series.getValues() == null) {
          continue;
        }

        for(List<Object> record : series.getValues()) {
          if(record == null) {
            continue;
          }

          matches.add( gson.fromJson( Utils.getValue(Utils.JSON_CONTENT, series, record), Attack.class ) );
        }

      }
    }

    return matches;
  }

  private void ensureInitialized() {
    if(! initializedProperly) {
      throw new IllegalStateException(Utils.getUninitializedMessage(environment));
    }
  }

  @PostConstruct
  public void ensureEnvironmentVariablesSet() {
    initializedProperly = Utils.isInitializedProperly(environment);

    if(initializedProperly) {
      influxDB = InfluxDBFactory.connect(environment.getProperty(Utils.INFLUXDB_CONNECTION_STRING),
                                         environment.getProperty(Utils.INFLUXDB_USERNAME),
                                         environment.getProperty(Utils.INFLUXDB_PASSWORD));

      Utils.createDatabaseIfNotExists(influxDB);
    } else {
      String msg = Utils.getUninitializedMessage(environment);

      if(logger != null) {
        logger.error(msg);
      } else {
        System.err.println(msg);
      }
    }
  }

}
