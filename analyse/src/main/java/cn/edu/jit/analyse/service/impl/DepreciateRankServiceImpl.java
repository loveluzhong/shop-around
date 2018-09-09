package cn.edu.jit.analyse.service.impl;

import cn.edu.jit.analyse.config.SparkConfig;
import cn.edu.jit.analyse.pojo.DO.DepreciateDO;
import cn.edu.jit.analyse.service.DepreciateRankService;
import cn.edu.jit.analyse.service.RedisRankService;
import cn.edu.jit.reptile.pojo.DO.CommodityDO;
import com.mongodb.spark.MongoSpark;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author LuZhong
 */
@Service
@Slf4j
public class DepreciateRankServiceImpl implements DepreciateRankService {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    @Autowired
    private SparkConfig sparkConfig;

    @Autowired
    private RedisRankService redisRankService;

    @PostConstruct
    public void init() {
        System.setProperty("hadoop.home.dir", sparkConfig.getHadoopHomeDir());
    }

    /**
     * 常见SparkSession
     * @return
     */
    private SparkSession createSparkSession() {
        return SparkSession.builder()
                .config("spark.driver.host", sparkConfig.getSparkDriverHost())
                .master("local")
                .appName("MongoSparkConnectorIntro")
                .config("spark.mongodb.input.uri", sparkConfig.getSparkMongodbInputUri())
                .config("spark.mongodb.output.uri", sparkConfig.getSparkMongodbOutputUri())
                .getOrCreate();
    }

    /**
     * 分析指定时间的价格数据与当前价格数据的降价额度排行榜
     * @param sparkSession
     * @param currentData 待对比数据
     * @param time 对日期
     * @return
     */
    private Map<String, List<DepreciateDO>> getDayRankByDays(SparkSession sparkSession, Dataset<Row> currentData, LocalDateTime time) {
        // 对比数据集
        Dataset<Row> comparisonData = sparkSession.sql("SELECT _id, name, url, imgUrl, shopName, category, inline(prices) FROM commodity").filter("time = '" + time.format(FORMATTER) + " 23" + "' AND price > 0");
        Dataset<Row> tmpData = currentData.join(comparisonData, currentData.col("_id").equalTo(comparisonData.col("_id")), "left")
                .select(currentData.col("_id"), currentData.col("name"), currentData.col("url"), currentData.col("imgUrl"), currentData.col("shopName"), currentData.col("category"), comparisonData.col("price").$minus(currentData.col("price")).as("depreciate"), currentData.col("time")).alias("tmp");
        // 创建临时视图
        tmpData.createOrReplaceTempView("tmp");
        return tmpData.sqlContext().sql("SELECT _id, name, url, imgUrl, shopName, category, depreciate FROM (" +
                "SELECT _id, name, url, imgUrl, shopName, category, depreciate, row_number() OVER (PARTITION BY category ORDER BY depreciate DESC) rank FROM tmp" +
                ") top_n WHERE depreciate > 0 AND rank <= " + sparkConfig.getRankNumber())
                .as(Encoders.bean(DepreciateDO.class))
                .collectAsList()
                .stream()
                .collect(Collectors.groupingBy(DepreciateDO::getCategory, Collectors.toList()));
    }

    @Override
    public void dayRank(LocalDateTime time) {
        SparkSession sparkSession = createSparkSession();
        JavaSparkContext jsc = new JavaSparkContext(sparkSession.sparkContext());
        Dataset<CommodityDO> explicitDS = MongoSpark.load(jsc).toDS(CommodityDO.class);
        // 创建商品视图
        explicitDS.createOrReplaceTempView("commodity");
        // 待对比数据集
        Dataset<Row> currentData = sparkSession.sql("SELECT _id, name, url, imgUrl, shopName, category, inline(prices) FROM commodity").filter("time = '" + FORMATTER.format(time) + " 21" + "' AND price > 0");
        // 将排行榜存入redis
        sparkConfig.getRankDays().forEach(day -> redisRankService.addRankToRedisByDay(getDayRankByDays(sparkSession, currentData, time.minusDays(day)), day));
        jsc.close();
    }
}