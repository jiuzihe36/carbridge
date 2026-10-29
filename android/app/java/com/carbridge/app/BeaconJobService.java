package com.carbridge.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 看门狗的第二条命：定时任务把 SDP 信标服务拉回来。
 */
public final class BeaconJobService extends JobService {

    private static final String TAG = "carbridge";
    /** 15 分钟是 JobScheduler 周期任务的最小间隔。 */
    private static final long PERIOD_MS = 15 * 60 * 1000L;
    private static final int JOB_ID = 0x0CAB;

    /** 幂等：重复调度只会更新同一个任务。 */
    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, BeaconJobService.class))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            js.schedule(job);
        } catch (Throwable t) {
            Log.w(TAG, "看门狗任务调度失败：" + t);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "看门狗任务触发 → 拉起 SDP 信标服务");
        SdpBeaconService.start(this);
        // 任务本身瞬时完成；真正的常驻靠服务
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // 被中断就重新排队
    }
}
