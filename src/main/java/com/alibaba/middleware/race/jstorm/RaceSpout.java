package com.alibaba.middleware.race.jstorm;

import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import com.alibaba.jstorm.common.metric.AsmHistogram;
import com.alibaba.jstorm.metric.MetricClient;
import com.alibaba.jstorm.utils.JStormUtils;
import com.alibaba.middleware.race.RaceConfig;
import com.alibaba.middleware.race.RaceUtils;
import com.alibaba.middleware.race.model.PaymentMessage;
import com.alibaba.middleware.race.model.SimplePaymentMessage;

import com.alibaba.middleware.race.model.OrderMessage;
import com.alibaba.middleware.race.rocketmq.MetaConsumerFactory;
import com.alibaba.middleware.race.rocketmq.RocketMQClientConfig;
import com.alibaba.rocketmq.client.consumer.DefaultMQPushConsumer;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.alibaba.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import com.alibaba.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.HashMap;

public class RaceSpout implements IRichSpout, MessageListenerConcurrently {
    /**
     *
     */
//    private static final long serialVersionUID = 1L;


    private static Logger LOG = LoggerFactory.getLogger(RaceSpout.class);


    private static int timestampToMinutestamp(long timestamp) {
        return (int) (timestamp / 60000) * 60;
    }

    protected transient SpoutOutputCollector collector;


    protected String id;
    protected boolean flowControl;
    protected boolean autoAck;

    protected transient LinkedBlockingDeque<SimplePaymentMessage> sendingQueue;

    protected transient MetricClient metricClient;
    protected transient AsmHistogram waithHistogram;
    protected transient AsmHistogram processHistogram;

    protected transient DefaultMQPushConsumer consumer;

    protected transient HashMap<Long, Short> Taobaohashmap;
    protected transient HashMap<Long, Short> Tmallhashmap;

    long sendingCount;
    long startTime;
    boolean isStatEnable;
    int sendNumPerNexttuple;

    public RaceSpout() {

    }

    public void initMetricClient(TopologyContext context) {
        metricClient = new MetricClient(context);
        waithHistogram = metricClient.registerHistogram("MetaTupleWait", null);
        processHistogram = metricClient.registerHistogram("MetaTupleProcess",
                null);
    }


    @Override
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        this.collector = collector;

        this.Taobaohashmap =  new HashMap<Long, Short>();
        this.Tmallhashmap =  new HashMap<Long, Short>();
        

        this.id = context.getThisComponentId() + ":" + context.getThisTaskId();

        this.sendingQueue = new LinkedBlockingDeque<SimplePaymentMessage>();


        this.flowControl = JStormUtils.parseBoolean(
                conf.get(RocketMQClientConfig.META_SPOUT_FLOW_CONTROL), true);
        this.autoAck = JStormUtils.parseBoolean(
                conf.get(RocketMQClientConfig.META_SPOUT_AUTO_ACK), true);

        StringBuilder sb = new StringBuilder();
        sb.append("Begin to init MetaSpout:").append(id);
        sb.append(", flowControl:").append(flowControl);
        sb.append(", autoAck:").append(autoAck);
        LOG.info(sb.toString());

        initMetricClient(context);

        RocketMQClientConfig clientConfig = RocketMQClientConfig.mkInstance(conf);

        try {
            consumer = MetaConsumerFactory.mkInstance(clientConfig, this);
        } catch (Exception e) {
            LOG.error("Failed to create Meta Consumer ", e);
            throw new RuntimeException("Failed to create MetaConsumer" + id, e);
        }

        if (consumer == null) {
            LOG.warn(id
                    + " already exist consumer in current worker, don't need to create again! ");

        }


        sendingCount = 0;
        startTime = System.currentTimeMillis();
        sendNumPerNexttuple = JStormUtils.parseInt(conf.get("send.num.each.time"), 1);
        isStatEnable = JStormUtils.parseBoolean(conf.get("is.stat.enable"), false);

        LOG.info("Successfully init " + id);
    }

    @Override
    public void nextTuple() {
//        orgin editon
//        int n = sendNumPerNexttuple;
//        while (--n >= 0) {
//            Utils.sleep(10);
//            String sentence = CHOICES[_rand.nextInt(CHOICES.length)];
//            _collector.emit(new Values(sentence));
//        }
//        updateSendTps();

        LOG.info("[*] step -4 ");

        SimplePaymentMessage paymentMessage = null;

        try {
            paymentMessage = sendingQueue.take();
            System.out.println("[*] Taking from Queue.");
        } catch (Exception e) {
            System.out.println("Taking from queue Wrong!");
        }
        LOG.info("[*] step -3 ");
        if (paymentMessage == null) {
            LOG.info("[*] step -2.1 ");
            System.out.println("[*] PaymentMessage = null");
            return;

        } else {

            LOG.info("[*] step -2.2 ");

            sendPaymentMessage(paymentMessage);
            System.out.println("[*] Simple Message Sending now:" + String.valueOf(paymentMessage.getOrderId()) + " , " + String.valueOf(paymentMessage.getMinutestamp()));
        }
    }

    private void sendPaymentMessage(SimplePaymentMessage paymentMessage) {
        if (paymentMessage == null) {
            return;
        }

        LOG.info("[*] step -1 ");
        sendPaymentMsg(paymentMessage);


        Short isTaobao = Taobaohashmap.get(paymentMessage.getOrderId());
        if ( isTaobao != null && isTaobao == 1 ) {
            sendTaobaoMsg(paymentMessage);
        } else if (Tmallhashmap.get(paymentMessage.getOrderId()) != null) {
            sendTmallMsg(paymentMessage);
        } else {

        }

    }

    @Override
    public void ack(Object id) {
        LOG.warn("Shouldn't go this function(function ack)");
    }

    @Override
    public void fail(Object id) {
        LOG.warn("Shouldn't go this function(function fail)");
//        _collector.emit(new Values(id), id);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {

//        declarer.declareStream(RaceConfig.MqPayTopic, new Fields(RaceConfig.Minutestamp, RaceConfig.PaymentAmount, RaceConfig.Platform));
        declarer.declareStream(RaceConfig.MqTaobaoTradeTopic, new Fields(RaceConfig.Minutestamp, RaceConfig.PaymentAmount));
        declarer.declareStream(RaceConfig.MqTmallTradeTopic, new Fields(RaceConfig.Minutestamp, RaceConfig.PaymentAmount));
    }

    private void updateSendTps() {
        if (!isStatEnable)
            return;

        sendingCount++;
        long now = System.currentTimeMillis();
        long interval = now - startTime;
        if (interval > 60 * 1000) {
            LOG.info("Sending tps of last one minute is " + (sendingCount * sendNumPerNexttuple * 1000) / interval);
            startTime = now;
            sendingCount = 0;
        }
    }

    @Override
    public void close() {
        if (consumer != null) {
            consumer.shutdown();
        }

    }

    @Override
    public void activate() {
        if (consumer != null) {
            consumer.resume();
        }

    }

    @Override
    public void deactivate() {
        if (consumer != null) {
            consumer.suspend();
        }
    }


    @Override
    public Map<String, Object> getComponentConfiguration() {
        // TODO Auto-generated method stub
        return null;
    }

    public void sendTaobaoMsg(SimplePaymentMessage message) {

        collector.emit(RaceConfig.MqTaobaoTradeTopic, new Values(message.getMinutestamp(), message.getPayAmount()));
    }

    public void sendTmallMsg(SimplePaymentMessage message) {

        collector.emit(RaceConfig.MqTmallTradeTopic, new Values(message.getMinutestamp(), message.getPayAmount()));
    }

    public void sendPaymentMsg(SimplePaymentMessage message) {

        LOG.info("[*] step 0 ");
        LOG.info(message.getMinutestamp() + "," + message.getPayAmount());
        collector.emit(RaceConfig.MqPayTopic, new Values(message.getMinutestamp(), message.getPayAmount()), message.getPayPlatform());
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : msgs) {
            byte[] body = msg.getBody();
            String topic = msg.getTopic();
            System.out.println("[*] Cosuming topic: " + topic);
            if (topic.equals(RaceConfig.MqPayTopic)) {
                //Payment Msg.
                SimplePaymentMessage temp = new SimplePaymentMessage();
                if (body.length == 2 && body[0] == 0 && body[1] == 0) {
                    //Info: 生产者停止生成数据, 并不意味着马上结束
                    System.out.println("Got the end signal of " + RaceConfig.MqPayTopic);
                    temp.setMinutestamp(0);  // timestamp os 0 represents ending.
                } else {
                    try {

                        PaymentMessage paymentMessage = RaceUtils.readKryoObject(PaymentMessage.class, body);
                        //日志会很大
//                        System.out.println("[*]Origin: " + paymentMessage.toString());
                        temp.setMinutestamp(timestampToMinutestamp(paymentMessage.getCreateTime()));
                        temp.setOrderId(paymentMessage.getOrderId());
                        temp.setPayAmount(paymentMessage.getPayAmount());
                        temp.setPayPlatform(paymentMessage.getPayPlatform());
                    } catch (Exception e) {
                        System.out.println("DeSerialize Failed!");
                    }
                }

                try {
                    sendingQueue.offer(temp);

                    LOG.info("[*] Sending queue of paytopic successfully!");
                } catch (Exception e) {
                    System.out.println("offer failed");
                    e.printStackTrace();
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }

            } else if (topic.equals(RaceConfig.MqTaobaoTradeTopic)) {
                //Taobao Msg.
                if (body.length == 2 && body[0] == 0 && body[1] == 0) {

                    //Info: 生产者停止生成数据, 并不意味着马上结束
                    System.out.println("Got the end signal of " + RaceConfig.MqTaobaoTradeTopic);
                    continue;
                }

                try {
                    OrderMessage taobaoMessage = RaceUtils.readKryoObject(OrderMessage.class, body);

                    if (taobaoMessage != null) {
                        //集群运行时这里输出的话日志会很大
//                        System.out.println("[*] taobaoMessage: " + taobaoMessage.toString());


                        try {


                            Taobaohashmap.put(taobaoMessage.getOrderId(), (short) 1);
                            LOG.info("Put into TaobaoHashmap successfully!");
                        } catch (Exception e) {
                            LOG.info("Put into TaobaoHashmap failed!");
                            e.printStackTrace();
                        }

                    } else {
                        System.out.println("[*] taobaoMessage is None.");
                    }
                } catch (Exception e) {
                    System.out.println("DeSerialize Failed!");
                    e.printStackTrace();
                }


            } else if (topic.equals(RaceConfig.MqTmallTradeTopic)) {
                //Tmall Msg.
                if (body.length == 2 && body[0] == 0 && body[1] == 0) {
                    //Info: 生产者停止生成数据, 并不意味着马上结束
                    System.out.println("Got the end signal of " + RaceConfig.MqTmallTradeTopic);
                    continue;
                }
                try {
                    OrderMessage tmallMessage = RaceUtils.readKryoObject(OrderMessage.class, body);
                    if (tmallMessage != null) {
                        //集群运行时这里输出的话日志会很大
//                        System.out.println("[*] TmallMessage: " + tmallMessage.toString());
                        try {
                            Tmallhashmap.put(tmallMessage.getOrderId(), (short) 1);
                            LOG.info("Put into TmallHashmap successfully!");
                        } catch (Exception e) {
                            System.out.println("Write-in Hashmap failed.");
                            e.printStackTrace();
                        }

                    } else {
                        System.out.println("[*] tmallMessage is None.");
                    }
                } catch (Exception e) {
                    System.out.println("DeSerialize Failed!");
                    e.printStackTrace();
                }

            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
