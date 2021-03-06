package com.ke.kob.admin.processor.kernel;

import com.alibaba.fastjson.JSONObject;
import com.ke.kob.admin.core.common.AdminConstant;
import com.ke.kob.admin.core.common.AdminLogConstant;
import com.ke.kob.admin.core.model.db.JobCron;
import com.ke.kob.admin.core.model.db.TaskRecord;
import com.ke.kob.admin.core.model.db.TaskWaiting;
import com.ke.kob.admin.core.model.oz.ClientInfo;
import com.ke.kob.admin.core.model.oz.MasterElectorNotice;
import com.ke.kob.admin.core.model.oz.NodeServer;
import com.ke.kob.admin.core.model.oz.ProcessorProperties;
import com.ke.kob.admin.core.service.ScheduleService;
import com.ke.kob.basic.constant.ZkPathConstant;
import com.ke.kob.basic.model.ClientData;
import com.ke.kob.basic.model.ClientPath;
import com.ke.kob.basic.model.TaskBaseContext;
import com.ke.kob.basic.support.KobUtils;
import com.ke.kob.basic.support.NamedThreadFactory;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.ZkClient;

import java.util.*;
import java.util.concurrent.*;

/**
 * 服务端作业调度执行器
 *
 * @Author: zhaoyuguang
 * @Date: 2018/8/10 下午12:21
 */

public @NoArgsConstructor @Slf4j class ServerProcessor {

    private static final ScheduledExecutorService CRON_TASK_EXECUTOR = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("cron-task", true));
    private static final ScheduledExecutorService WAITING_TASK_EXECUTOR = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("waiting-task", true));
    private static final ScheduledExecutorService EXPIRE_TASK_EXECUTOR = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("expire-task", true));
    private static final ScheduledExecutorService SERVER_HEARTBEAT = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("kob-server-heartbeat", true));
    /**
     * 执行任务过期线程
     */
    private static final Integer CURSOR_100 = 100;
    private ZkClient zkClient;
    private ProcessorProperties processorProperties;
    private ServerContext serverContext;
    private ScheduleService scheduleService;

    /**
     * 初始化配置信息
     *
     * @param processorProperties yml中配置的
     * @param zkClient            101tech ZkClient
     * @param scheduleService     ScheduleService接口
     */
    public void initializeAttributes(ProcessorProperties processorProperties, ZkClient zkClient, ScheduleService scheduleService, ServerContext serverContext) {
        this.processorProperties = processorProperties;
        this.serverContext = serverContext;
        this.zkClient = zkClient;
        this.scheduleService = scheduleService;
    }

    /**
     * 初始化服务端在ZK节点的路径
     * 开启服务端master选举
     * 并将自己的节点细心注册上去
     */
    public void initializeEnvironment() {
        String masterNodePath = serverContext.getMasterPath();
        if (!zkClient.exists(masterNodePath)) {
            zkClient.createPersistent(masterNodePath, true);
        }
        zkClient.subscribeChildChanges(masterNodePath, new IZkChildListener() {
            @Override
            public void handleChildChange(String parentPath, List<String> currentChilds) throws Exception {
                serverContext.getMasterElector().elector(currentChilds);
            }
        });
        zkClient.createEphemeral(serverContext.getLocalNodePath());
    }

    /**
     * schedule线程：cron类型作业生成未来指定时间间隔内的待执行任务
     */
    void initializeCornTaskExecutor() {
        CRON_TASK_EXECUTOR.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (serverContext.isMaster()) {
                    try {
                        jobCronGenerateWaitingTask();
                    } catch (Exception e) {
                        log.error(AdminLogConstant.error9100(), e);
                    }
                }
            }
        }, processorProperties.getCronTaskExecutorInitialDelaySec(), processorProperties.getCronTaskExecutorPeriodSec(), TimeUnit.SECONDS);
    }

    /**
     * cron类型作业生成未来指定时间间隔内的待执行任务
     * 遍历未暂停的cron类型作业，
     * 通过事务生成未来一定时间内的作业，并更新cron任务的最后生成触发时间
     */
    private void jobCronGenerateWaitingTask() {
        List<JobCron> jobCronList = scheduleService.findRunningCronJob(serverContext.getCluster());
        if (!KobUtils.isEmpty(jobCronList)) {
            Date now = new Date();
            for (JobCron jobCron : jobCronList) {
                try {
                    scheduleService.createCronWaitingTaskForTime(serverContext.getLocalIdentification(), jobCron, processorProperties.getAppendPreviousTask(), processorProperties.getIntervalMin(), serverContext.getCluster(), now);
                } catch (Exception e) {
                    log.error(AdminLogConstant.error9101(JSONObject.toJSONString(jobCron)), e);
                }
            }
        }
    }

    /**
     * schedule线程: 推送等待执行的任务
     */
    void initializeWaitingTaskExecutor() {
        WAITING_TASK_EXECUTOR.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (serverContext.isMaster()) {
                    try {
                        pushWaitingTask();
                    } catch (Exception e) {
                        log.error(AdminLogConstant.error9104(), e);
                    }

                }
            }
        }, processorProperties.getWaitingTaskExecutorInitialDelayMs(), processorProperties.getWaitingTaskExecutorPeriodMs() * 20, TimeUnit.MILLISECONDS);
    }

    /**
     * 推送等待执行的任务
     * 锁定待推送任务 lockPushTask
     * 推送任务 pushTask
     */
    private void pushWaitingTask() {
        long now = System.currentTimeMillis();
        List<TaskWaiting> taskWaitingList = scheduleService.findTriggerTaskInLimit(now, processorProperties.getWaitingTaskScroll(), serverContext.getCluster());
        if (!KobUtils.isEmpty(taskWaitingList)) {
            for (final TaskWaiting taskWaiting : taskWaitingList) {
                try {
                    Boolean lastTaskComplete = scheduleService.lockPushTask(taskWaiting, serverContext.getCluster(), serverContext.getLocalIdentification());
                    if (taskWaiting.getRely() && lastTaskComplete != null && !lastTaskComplete) {
                        continue;
                    }
                } catch (Exception e) {
                    log.error(AdminLogConstant.error9102(JSONObject.toJSONString(taskWaiting)), e);
                    continue;
                }
                try {
                    scheduleService.pushTask(zkClient, taskWaiting, serverContext.getCluster());
                    recoveryOverstockTask(taskWaiting.getProjectCode());
                } catch (Exception e) {
                    log.error(AdminLogConstant.error9103(JSONObject.toJSONString(taskWaiting)), e);
                }
            }
        }
    }

    /**
     * 待回收积压任务
     * 根据一定权重进入回收方法，判断是否超过积压阈值，并回收已过期任务只可剩余任务数量
     *
     * @param projectCode 项目名称
     */
    private void recoveryOverstockTask(String projectCode) {
        int random100 = new Random().nextInt(AdminConstant.ONE_HUNDRED);
        if (random100 < processorProperties.getTaskOverstockRecoveryWeight()) {
            List<String> taskPathList = zkClient.getChildren(ZkPathConstant.clientTaskPath(serverContext.getCluster(), projectCode));
            if (!KobUtils.isEmpty(taskPathList) && taskPathList.size() > processorProperties.getTaskOverstockRecoveryThreshold()) {
                List<TaskBaseContext> tasks = new ArrayList<>();
                for (String s : taskPathList) {
                    TaskBaseContext task = JSONObject.parseObject(s, TaskBaseContext.class);
                    task.setPath(ZkPathConstant.clientTaskPath(serverContext.getCluster(), projectCode) + ZkPathConstant.BACKSLASH + s);
                    tasks.add(task);
                }
                Collections.sort(tasks);
                List<TaskBaseContext> overstockTask = tasks.subList(0, tasks.size() - processorProperties.getTaskOverstockRecoveryRetainCount());
                scheduleService.fireOverstockTask(zkClient, overstockTask, serverContext.getCluster());
            }
        }
    }

    /**
     * 心跳线程 客户端信息校准，服务端续约
     */
    void heartbeat() {
        SERVER_HEARTBEAT.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!zkClient.exists(serverContext.getMasterPath())) {
                        log.warn("父节点不存在");
                        return;
                    }
                    if (!zkClient.exists(serverContext.getLocalNodePath())) {
                        zkClient.createEphemeral(serverContext.getLocalNodePath());
                        log.warn("我的节点不存在");
                        return;
                    }
                    List<String> currentChilds = zkClient.getChildren(ZkPathConstant.serverNodePath(serverContext.getCluster()));
                    NodeServer currentNodeServerMaster = MasterElector.getNodeMaster(currentChilds);
                    if (!currentNodeServerMaster.getIdentification().equals(serverContext.getMasterElector().getMaster().getIdentification())) {
                        zkClient.writeData(serverContext.getLocalNodePath(), new MasterElectorNotice(serverContext.getLocalIdentification()));
                        log.warn("选举可能存在问题 从新选举 好像不能触发watch");
                    }
                    log.warn("心跳");
                } catch (Exception e) {
                    log.error("心跳 error", e);
                }
                try {
                    Set<String> currentProjectCodeSet = scheduleService.selectServiceProjectCodeSet();
                    Set<String> localProjectCodeSet = serverContext.getProjectCodeSet();
                    if (KobUtils.isEmpty(currentProjectCodeSet)) {
                        for (final String currentProjectCode : currentProjectCodeSet) {
                            if (localProjectCodeSet.add(currentProjectCode)) {
                                zkClient.subscribeChildChanges(ZkPathConstant.clientNodePath(serverContext.getCluster(), currentProjectCode), new IZkChildListener() {
                                    @Override
                                    public void handleChildChange(String parentPath, List<String> currentChilds) throws Exception {
                                        refreshClientNode(currentChilds, currentProjectCode);
                                    }
                                });
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("心跳 error", e);
                }
            }
        }, processorProperties.getHeartbeatInitialDelaySec(), processorProperties.getHeartbeatPeriodSec(), TimeUnit.SECONDS);
    }

    private void refreshClientNode(List<String> currentChilds, String project) {
        Map<String, ClientInfo> projectClientNode = new ConcurrentHashMap<>();
        if (!KobUtils.isEmpty(currentChilds)) {
            for (String child : currentChilds) {
                ClientPath clientPath = JSONObject.parseObject(child, ClientPath.class);
                String path = ZkPathConstant.clientNodePath(serverContext.getCluster(), project) + ZkPathConstant.BACKSLASH + child;
                String dataStr = zkClient.readData(path, true);
                if (!KobUtils.isEmpty(dataStr)) {
                    ClientData clientData = JSONObject.parseObject(dataStr, ClientData.class);
                    projectClientNode.put(clientPath.getIdentification(), new ClientInfo(path, clientPath, clientData));
                }
            }
        }
        serverContext.getClientNodeMap().put(project, projectClientNode);
    }

    public void initializeExpireTaskExecutor() {
        EXPIRE_TASK_EXECUTOR.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    if (serverContext.isMaster()) {
                        long now = System.currentTimeMillis();
                        int expireCount = scheduleService.selectCountExpireTaskRecord(now, serverContext.getCluster());
                        if (expireCount > 0) {
                            int start = expireCount / CURSOR_100 * CURSOR_100;
                            int limit = expireCount - start;
                            do {
                                List<TaskRecord> taskExpireList = scheduleService.selectListExpireTaskRecord(now, start, limit, serverContext.getCluster());
                                if (KobUtils.isEmpty(taskExpireList)) {
                                    start = start - CURSOR_100;
                                    limit = CURSOR_100;
                                    continue;
                                }
                                for (TaskRecord taskExpire : taskExpireList) {
                                    scheduleService.handleExpireTask(zkClient, taskExpire, serverContext.getCluster());
                                }
                                start = start - CURSOR_100;
                                limit = CURSOR_100;
                            } while (start >= 0);
                        }
                    }
                } catch (Exception e) {
                    log.error("server_admin_code_error_102:过期数据计算异常", e);
                }
            }
        }, processorProperties.getHeartbeatInitialDelaySec(), processorProperties.getHeartbeatPeriodSec(), TimeUnit.SECONDS);
    }
}
