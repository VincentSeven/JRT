package com.alibaba.middleware.race.Tair;

import java.io.Serializable;

import com.alibaba.middleware.race.RaceConfig;
import com.taobao.tair.ResultCode;
import com.taobao.tair.impl.DefaultTairManager;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读写tair所需要的集群信息，如masterConfigServer/slaveConfigServer地址/
 * group 、namespace我们都会在正式提交代码前告知选手
 */

public class TairOperatorImpl implements Serializable{

    private static Logger LOG = LoggerFactory.getLogger(TairOperatorImpl.class);
    public static DefaultTairManager tairManager = new DefaultTairManager();

    private static final int namespace = RaceConfig.TairNamespace;

    public TairOperatorImpl() {
        List<String> confServer = new ArrayList<String>();
        confServer.add(RaceConfig.TairConfigServer);
        if (RaceConfig.TairSlaveConfigServer != ""){
            confServer.add(RaceConfig.TairSlaveConfigServer);
        }

        TairOperatorImpl.tairManager.setConfigServerList(confServer);
        TairOperatorImpl.tairManager.setGroupName(RaceConfig.TairGroup);
        TairOperatorImpl.tairManager.init();
    }
    
    public static boolean write(String key, Double value) {
        ResultCode statuscode = tairManager.put(namespace,key,value);  //put is a function of Tair.
        if(statuscode.isSuccess())
        {
            LOG.info("[*] {" + key + ":" + value + "} Has been Stored Successfully.");
            return true;
        }

        LOG.info("[*] {" + key + ":" + value + "} Has not been Stored Successfully.");
        return false;

    }

    public Object get(Serializable key) {
        return null;
    }

    public boolean remove(Serializable key) {
        return false;
    }

    public void close(){
    }

    //天猫的分钟交易额写入tair

}
