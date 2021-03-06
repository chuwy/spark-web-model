object WebModel {

  def main(args: Array[String]) {

    val inputDir = "xxx"

    import org.apache.spark.SparkConf
    val conf = new SparkConf().setAppName("sparkDataModeling").setMaster("local[*]")

    import org.apache.spark.SparkContext
    val sc = new SparkContext(conf)

    sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", "xxx")
    sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", "xxx")

    val inputRDD = sc.textFile(inputDir)

    import com.snowplowanalytics.snowplow.analytics.scalasdk.json.EventTransformer

    val eventsRDD = inputRDD
      .map(line => EventTransformer.transform(line))
      .filter(_.isRight)
      .flatMap(_.right.toOption).
      persist

    import org.apache.spark.sql.SparkSession
    val spark = SparkSession
      .builder()
      .getOrCreate()

    import spark.implicits._
    val df = spark.read.json(eventsRDD)
    df.registerTempTable("atomic_events")

    //00-web-page-context

    val dfWebPageContext = spark.sql(
      """
      WITH prep AS
      (
        SELECT
          event_id AS root_id,
          contexts_com_snowplowanalytics_snowplow_web_page_1[0] AS page_view_id
        FROM
          atomic_events
        GROUP BY 1, 2
      )

      SELECT
        *
      FROM
        prep
      WHERE
        root_id NOT IN (SELECT root_id FROM prep GROUP BY 1 HAVING COUNT(*) > 1) -- exclude all root ID with more than one page view ID
      """
    )
    dfWebPageContext.registerTempTable("scratch_web_page_context")

    //01-events

    val dfEvents = spark.sql(
      """
      -- select the relevant dimensions from atomic_events

      WITH prep AS
      (
        SELECT
          ev.user_id,
          ev.domain_userid,
          ev.network_userid,
          ev.domain_sessionid,
          ev.domain_sessionidx,
          wp.page_view_id,
          ev.page_title,
          ev.page_urlscheme,
          ev.page_urlhost,
          ev.page_urlport,
          ev.page_urlpath,
          ev.page_urlquery,
          ev.page_urlfragment,
          ev.refr_urlscheme,
          ev.refr_urlhost,
          ev.refr_urlport,
          ev.refr_urlpath,
          ev.refr_urlquery,
          ev.refr_urlfragment,
          ev.refr_medium,
          ev.refr_source,
          ev.refr_term,
          ev.mkt_medium,
          ev.mkt_source,
          ev.mkt_term,
          ev.mkt_content,
          ev.mkt_campaign,
          ev.mkt_clickid,
          ev.mkt_network,
          ev.geo_country,
          ev.geo_region,
          ev.geo_region_name,
          ev.geo_city,
          ev.geo_zipcode,
          ev.geo_latitude,
          ev.geo_longitude,
          ev.geo_timezone,
          ev.user_ipaddress,
          ev.ip_isp,
          ev.ip_organization,
          ev.ip_domain,
          ev.ip_netspeed,
          ev.app_id,
          ev.useragent,
          ev.br_name,
          ev.br_family,
          ev.br_version,
          ev.br_type,
          ev.br_renderengine,
          ev.br_lang,
          ev.dvce_type,
          ev.dvce_ismobile,
          ev.os_name,
          ev.os_family,
          ev.os_manufacturer,
          ev.os_timezone,
          ev.name_tracker, -- included to filter on
          ev.dvce_created_tstamp -- included to sort on
        FROM
          atomic_events AS ev
          INNER JOIN scratch_web_page_context AS wp -- an INNER JOIN guarantees that all rows have a page view ID
            ON ev.event_id = wp.root_id
        WHERE
          ev.platform = 'web'
          AND ev.event_name = 'page_view' -- filtering on page view events removes the need for a FIRST_VALUE function
        )

        -- more than one page view event per page view ID? select the first one

        SELECT
          *
        FROM
          (
            SELECT
              *,
              ROW_NUMBER () OVER (PARTITION BY page_view_id ORDER BY dvce_created_tstamp) AS n
            FROM
              prep
          )
        WHERE
          n = 1
      """
    )
    dfEvents.registerTempTable("scratch_events")

    // 02-events-time
    val dfEventsTime = spark.sql(
      """
        |SELECT
        |  wp.page_view_id,
        |  MIN(ev.derived_tstamp) AS min_tstamp, -- requires the derived timestamp (JS tracker 2.6.0+ and Snowplow 71+)
        |  MAX(ev.derived_tstamp) AS max_tstamp, -- requires the derived timestamp (JS tracker 2.6.0+ and Snowplow 71+)
        |  SUM(CASE WHEN ev.event_name = 'page_view' THEN 1 ELSE 0 END) AS pv_count, -- for debugging
        |  SUM(CASE WHEN ev.event_name = 'page_ping' THEN 1 ELSE 0 END) AS pp_count, -- for debugging
        |  10 * COUNT(DISTINCT(FLOOR(UNIX_TIMESTAMP(ev.derived_tstamp)/10))) - 10 AS time_engaged_in_s -- assumes 10 seconds between subsequent page pings
        |
        |FROM
        |  atomic_events AS ev
        |  INNER JOIN scratch_web_page_context AS wp
        |    ON ev.event_id = wp.root_id
        |
        |WHERE
        |  ev.event_name IN ('page_view', 'page_ping')
        |
        |GROUP BY 1
      """.stripMargin
    )
    dfEventsTime.registerTempTable("scratch_web_events_time")

    spark.sql("select * from scratch_web_events_time").show(3)


    /*
    spark.sql("SELECT * FROM web_page_context").show(5)

    spark.sql("SELECT contexts_com_snowplowanalytics_snowplow_web_page_1 FROM events").show(3)

    val dfVisitors = spark.sql("SELECT domain_userid, MAX(domain_sessionidx) AS sessions FROM events GROUP BY domain_userid")
    dfVisitors.registerTempTable("visitors")

    spark.sql("SELECT a.domain_userid, b.sessions, COUNT(*) AS count FROM events AS a LEFT JOIN visitors AS b ON a.domain_userid = b.domain_userid GROUP BY a.domain_userid, b.sessions").show(5)
    */
  }

}