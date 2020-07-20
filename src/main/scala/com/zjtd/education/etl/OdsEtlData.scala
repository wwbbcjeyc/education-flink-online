package com.zjtd.education.etl

import java.util.Properties

import com.alibaba.fastjson.JSONObject
import com.zjtd.education.model.{DwdKafkaProducerSerializationSchema, GlobalConfig, TopicAndValue, TopicAndValueDeserializationSchema}
import com.zjtd.education.util.ParseJsonData
import org.apache.flink.api.common.restartstrategy.RestartStrategies
import org.apache.flink.api.common.time.Time
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend
import org.apache.flink.runtime.state.StateBackend
import org.apache.flink.streaming.api.environment.CheckpointConfig
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.streaming.api.scala.{StreamExecutionEnvironment, _}
import org.apache.flink.streaming.api.{CheckpointingMode, TimeCharacteristic}
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer010, FlinkKafkaProducer010}
import org.apache.flink.util.Collector

//flink run -m yarn-cluster -ynm odsetldata -p 12 -ys 4  -yjm 1024 -ytm 2048m -d -c com.atguigu.education.etl.OdsEtlData -yqu flink ./education-flink-online-1.0-SNAPSHOT-jar-with-dependencies.jar --group.id test --bootstrap.servers hadoop101:9092,hadoop102:9092,hadoop103:9092 --topic basewebsite,basead,member,memberpaymoney,memberregtype,membervip

//--group.id test --bootstrap.servers hadoop101:9092,hadoop102:9092,hadoop103:9092 --topic basewebsite,basead,member,memberpaymoney,memberregtype,membervip
object OdsEtlData {
  val BOOTSTRAP_SERVERS = "bootstrap.servers"
  val GROUP_ID = "group.id"
  val RETRIES = "retries"
  val TOPIC = "topic"

  def main(args: Array[String]): Unit = {
    val params = ParameterTool.fromArgs(args)
    //    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val env = StreamExecutionEnvironment.createLocalEnvironmentWithWebUI()
    env.getConfig.setGlobalJobParameters(params)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime) //设置时间模式为事件时间

    //checkpoint设置
    env.enableCheckpointing(60000l) //1分钟做一次checkpoint
    val checkpointConfig = env.getCheckpointConfig
    checkpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE) //仅仅一次
    checkpointConfig.setMinPauseBetweenCheckpoints(30000l) //设置checkpoint间隔时间30秒
    checkpointConfig.setCheckpointTimeout(100000l) //设置checkpoint超时时间
    checkpointConfig.enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION) //cancel时保留checkpoint
    //设置statebackend 为rockdb
    //    val stateBackend: StateBackend = new RocksDBStateBackend("hdfs://nameservice1/flink/checkpoint")
    //    env.setStateBackend(stateBackend)

    //设置重启策略   重启3次 间隔10秒
    env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Time.seconds(10)))
    import scala.collection.JavaConverters._
    val topicList = params.get(TOPIC).split(",").toBuffer.asJava
    val consumerProps = new Properties()
    consumerProps.setProperty(BOOTSTRAP_SERVERS, params.get(BOOTSTRAP_SERVERS))
    consumerProps.setProperty(GROUP_ID, params.get(GROUP_ID))
    val kafaEventSource = new FlinkKafkaConsumer010[TopicAndValue](topicList, new TopicAndValueDeserializationSchema, consumerProps)
    kafaEventSource.setStartFromEarliest()

    val dataStream = env.addSource(kafaEventSource).filter(item => {
      //先过滤非json数据
      val obj = ParseJsonData.getJsonData(item.value)
      obj.isInstanceOf[JSONObject]
    })
    //将dataStream拆成两份 一份维度表写到hbase 另一份事实表数据写到第二层kafka
//    val sideOutHbaseTag = new OutputTag[TopicAndValue]("hbaseSinkStream")
    //    val sideOutGreenPlumTag = new OutputTag[TopicAndValue]("greenplumSinkStream")
        val sideOutKuduTag = new OutputTag[TopicAndValue]("kuduSinkStream")
    val result = dataStream.process(new ProcessFunction[TopicAndValue, TopicAndValue] {
      override def processElement(value: TopicAndValue, ctx: ProcessFunction[TopicAndValue, TopicAndValue]#Context, out: Collector[TopicAndValue]): Unit = {
        value.topic match {
          case "basead" | "basewebsite" | "membervip" => ctx.output(sideOutKuduTag, value)
          case _ => out.collect(value)
        }
      }
    })
    //侧输出流得到 需要写入hbase的数据
    result.getSideOutput(sideOutKuduTag).addSink(new DwdKuduSink)
    //    //    //事实表数据写入第二层kafka
    result.addSink(new FlinkKafkaProducer010[TopicAndValue](GlobalConfig.BOOTSTRAP_SERVERS, "", new DwdKafkaProducerSerializationSchema))
    env.execute()
  }
}
