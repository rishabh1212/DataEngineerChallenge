/* DataChallenge.scala */
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{LongType, TimestampType}
import org.apache.spark.sql.DataFrame


object DataChallenge {
    def main(args: Array[String]) {
        val logFile = "/data/2015_07_22_mktplace_shop_web_log_sample.log" // Should be some file on your system
        val spark = SparkSession.builder.appName("Data Challenge").getOrCreate()
        val logData = spark.read.format("csv").option("header", "false").option("delimiter", " ").load(logFile)
        val logDataDF = logData.toDF("time", "elb", "user", "backend:port",
                                     "request_processing_time", "backend_processing_time",
                                     "response_processing_time", "elb_status_code", "backend_status_code",
                                     "received_bytes", "sent_bytes", "request", "user_agent", "ssl_cipher",
                                     "ssl_protocol")

        val sessLogDataDf = logDataDF.select("user", "time", "request").withColumn("time", col("time").cast(TimestampType)).toDF()

        // Assuming each partition is small enough to fit on one exectuor
        val sessTimeout = 15 * 60
        val partByUser = Window.partitionBy("user")

        // sessionize the data
        val sessTimeDataDf = sessLogDataDf.transform(sessionzeLogData(sessTimeout))


        /*
        Unique links clicked per session
        */
        val userSessUniqLinkCountDf = sessTimeDataDf.select(
           "user", "sess_num", "request"
        ).distinct.withColumn(
            "total_unique_url_per_sess",
            count("request").over(Window.partitionBy("user", "sess_num"))
        ).select("user", "sess_num", "total_unique_url_per_sess").distinct

        val userSessTimeDF = sessTimeDataDf.select("user","sess_num", "sess_time").distinct


        /*
        Calulate most engaged user by calculating total session time for each user and return max
        */
        val totalSessTimeDf = userSessTimeDF.withColumn(
            "total_sess_time",
            sum("sess_time").over(partByUser)
        ).select(
            "user",
            "total_sess_time"
        ).distinct

        val maxTotalSessTime = totalSessTimeDf.agg(max("total_sess_time")).first()(0)

        val mostEngagedUser = totalSessTimeDf.filter(
            col("total_sess_time").cast(LongType) === maxTotalSessTime
        ).select("user").distinct


        /*
        Average session time per user
        */
        val averageSessTimeOverall = userSessTimeDF.agg(avg("sess_time")).first()(0)

        val averageSessTimePerUser = userSessTimeDF.withColumn(
            "avg_sess_time",
            avg("sess_time").over(partByUser)
        ).select("user", "avg_sess_time").distinct


        /*
        Saving relevant stdout
        */
        save(userSessUniqLinkCountDf, "/data/userSessUniqLinkCountDf")
        save(mostEngagedUser, "/data/mostEngagedUser")
        save(averageSessTimePerUser, "/data/averageSessTimePerUser")

        spark.stop()
    }


    /*
    Sessionize log data
    1. Move time column down by one
    2. Check if time difference with previous request is less than timeout, then assign 1
    3. Do a cumulative sum and get session numbers for each user[local]
    4. start and end time of session are max and min of a session
    */
    def sessionzeLogData(sessTimeout: Int)(sessLogDataDf: DataFrame): DataFrame = {

        val partByUser = Window.partitionBy("user")

        sessLogDataDf.withColumn(
            // move column down by 1
            "pre_time",
            lag("time", 1).over(partByUser.orderBy(col("time").asc))
        ).withColumn(
            // if pre_event and time is within sessiontimeout, they belong to same session
            "new_sess",
            when(
                col("pre_time").isNull ||
                col("time").cast(LongType) - col("pre_time").cast(LongType) > sessTimeout,
                1
            ).otherwise(0)
        ).withColumn(
            // cumulative sum which makes sure rows in same session get same number
            "sess_num",
            sum("new_sess").over(partByUser.orderBy(col("time").asc))
        ).withColumn(
            // in a session min time start time
            "start_sess_time",
            min("time").over(Window.partitionBy("user", "sess_num"))
        ).withColumn(
            // in session max time is end time
            "end_sess_time",
            max("time").over(Window.partitionBy("user", "sess_num"))
        ).withColumn(
            "sess_time",
            col("end_sess_time").cast(LongType) - col("start_sess_time").cast(LongType)
        )
    }

    def save(df: DataFrame, name: String): Unit = {
        df.coalesce(1).write
        .format("csv")
        .option("header", "true")
        .mode("overwrite")
        .option("sep",",")
        .save(name)
    }

}
