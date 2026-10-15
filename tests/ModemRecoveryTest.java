package com.celllock.app;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.json.JSONObject;

/** Exercises the hardware test's actual cleanup with a fake backend; never calls Root. */
public final class ModemRecoveryTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static JSONObject baseline()throws Exception{return new JSONObject("{\"schema\":2,\"rat\":92,\"nrMode\":0,\"legacyMask\":\"0000000000000001\",\"lte\":\"3\",\"nrSa\":\"41\",\"nrNsa\":\"78\",\"gsm\":\"900\",\"wcdma\":\"1\"}");}
    private static BitSet bits(int value){BitSet out=new BitSet();out.set(value);return out;}
    private static ModemState state(boolean restored){ModemState s=new ModemState();s.prefKnown=true;s.rat=92;s.nrMode=0;s.legacyMask="0000000000000001";s.lte=bits(restored?3:1);s.nrSa=bits(41);s.nrNsa=bits(78);s.gsm=bits(900);s.wcdma=bits(1);return s;}
    private static final class Backend implements Function<String[],ModemResult> {
        final List<String> calls=new ArrayList<>();
        String reject="",crash="";
        boolean restored,failed;
        int reads;
        public ModemResult apply(String[] args){
            String name=args[0];calls.add(name);
            if(name.equals("state")){reads++;return new ModemResult(true,"state","",false,state(restored),"");}
            if(name.equals(crash)){failed=true;throw new IllegalStateException("injected "+name);}
            if(name.equals(reject)){failed=true;return ModemResult.failure("rejected","injected "+name,"");}
            if(name.equals("rat")&&!failed)restored=true;
            return new ModemResult(true,"verified","",true,state(restored),"");
        }
    }
    private static List<String> expected()throws Exception{
        List<String> out=new ArrayList<>();out.add("state");
        for(String[] command:Snapshots.restoreCommands(baseline(),0))if(!command[0].equals("cell-clear"))out.add(command[0]);
        out.add("state");return out;
    }
    private static void queuedWrites(boolean interrupt)throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor(),cleanup=Executors.newSingleThreadExecutor();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),backendEntered=new CountDownLatch(1);
        List<String> order=new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Thread> cleanupThread=new AtomicReference<>();
        AtomicBoolean restoredInterrupt=new AtomicBoolean(),wroteInterrupted=new AtomicBoolean();
        try{
            worker.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}order.add("pending-write-1");order.add("pending-write-2");});
            check(started.await(2,TimeUnit.SECONDS),"pending task did not start");
            Backend backend=new Backend();
            Future<?> recovered=cleanup.submit(()->{
                cleanupThread.set(Thread.currentThread());if(interrupt)Thread.currentThread().interrupt();
                try{ModemFlowTest.recoverBaseline(worker,baseline(),0,args->{backendEntered.countDown();order.add("recovery");if(Thread.currentThread().isInterrupted())wroteInterrupted.set(true);return backend.apply(args);});restoredInterrupt.set(Thread.currentThread().isInterrupted());}
                catch(Exception e){throw new AssertionError(e);}finally{Thread.interrupted();}
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!worker.isShutdown()&&backendEntered.getCount()!=0&&System.nanoTime()<deadline)Thread.yield();
            check(worker.isShutdown(),"cleanup reached backend before draining the Session worker");
            check(backendEntered.getCount()==1,"cleanup overlapped a pending Session task");
            if(interrupt)cleanupThread.get().interrupt();
            release.countDown();recovered.get(3,TimeUnit.SECONDS);
            check(worker.isTerminated(),"Session worker can still write after cleanup");
            check(order.subList(0,2).equals(List.of("pending-write-1","pending-write-2")),"late writes overwrote recovery");
            check(backend.calls.equals(expected()),"successful cleanup did not restore and verify all preferences");
            check(!wroteInterrupted.get()&&restoredInterrupt.get()==interrupt,"interrupt skipped recovery or was not restored after cleanup");
        }finally{release.countDown();worker.shutdownNow();cleanup.shutdownNow();check(worker.awaitTermination(3,TimeUnit.SECONDS)&&cleanup.awaitTermination(3,TimeUnit.SECONDS),"test threads did not exit");}
    }
    private static void partialFailure(boolean crash)throws Exception{
        Backend backend=new Backend();if(crash)backend.crash="restore-lte";else backend.reject="restore-lte";
        AssertionError failure=null;
        try{ModemFlowTest.recoverBaseline(null,baseline(),0,backend);}catch(AssertionError error){failure=error;}
        check(failure!=null,"failed recovery was reported as success");
        check(backend.calls.equals(expected()),"first failure skipped remaining recovery or final verification: "+backend.calls);
        check(backend.reads==2,"failed recovery skipped final readback");
        check(failure.getSuppressed().length>=2,"command failure and final mismatch were not both retained");
    }
    private static void alreadyRestored()throws Exception{
        Backend backend=new Backend();backend.restored=true;
        ModemFlowTest.recoverBaseline(null,baseline(),0,backend);
        check(backend.calls.equals(List.of("state","state")),"unchanged preferences were rewritten");
    }
    public static void main(String[] args)throws Exception{
        List<Throwable> failures=new ArrayList<>();
        for(int test=0;test<5;test++)try{if(test<2)queuedWrites(test==1);else if(test<4)partialFailure(test==3);else alreadyRestored();}catch(Throwable error){failures.add(error);System.err.println("FAIL: "+error);}
        if(!failures.isEmpty()){AssertionError error=new AssertionError(failures.size()+" cleanup scenarios failed");for(Throwable failure:failures)error.addSuppressed(failure);throw error;}
        System.out.println("PASS: "+checks+" modem cleanup checks; no hardware writes");
    }
}
