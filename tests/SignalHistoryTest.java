package com.celllock.app;

/** Runs on the host JDK; no phone or radio writes. */
public final class SignalHistoryTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        SignalHistory history=new SignalHistory();
        check(history.add(100_000,101_000,1_000_000,-100,"LTE:1650:321","LTE PCI 321"),"valid sample");
        check(history.samples().get(0).wallMillis==999_000,"wall time derives from modem timestamp, not callback time");
        check(!history.add(100_000,106_000,1_005_000,-99,"LTE:1650:321","LTE PCI 321"),"cached timestamp must not add a point");
        check(history.add(105_000,106_000,1_005_000,-100,"LTE:1650:321","LTE PCI 321"),"unchanged value with new timestamp is genuine data");
        check(!history.samples().get(1).breakBefore,"same cell connected");
        check(history.add(110_000,111_000,1_010_000,-102,"LTE:1650:333","LTE PCI 333"),"cell changed");
        check(history.samples().get(2).breakBefore,"PCI change breaks curve");
        history.markGap();history.add(115_000,116_000,1_015_000,-101,"LTE:1650:333","LTE PCI 333");
        check(history.samples().get(3).breakBefore,"missing/background sample breaks curve");
        history.add(140_000,141_000,1_040_000,-103,"LTE:1650:333","LTE PCI 333");
        check(history.samples().get(4).breakBefore,"long interval breaks curve");
        check(!history.add(142_000,141_000,1_040_000,-103,"LTE:1650:333","LTE PCI 333"),"future timestamp rejected");
        check(!history.add(0,141_000,1_040_000,-103,"LTE:1650:333","LTE PCI 333"),"unknown timestamp rejected");
        check(!history.add(141_000,142_000,1_041_000,Integer.MAX_VALUE,"LTE:1650:333","LTE PCI 333"),"unavailable signal rejected");
        check(!history.add(139_000,142_000,1_041_000,-103,"LTE:1650:333","LTE PCI 333"),"out-of-order sample rejected");
        SignalHistory restored=new SignalHistory();for(SignalHistory.Sample point:history.samples())restored.restore(point,142_000);
        check(restored.samples().size()==5,"rotation preserves actual history");
        check(!restored.add(140_000,143_000,1_042_000,-103,"LTE:1650:333","LTE PCI 333"),"rotation cache cannot duplicate");
        restored.add(145_000,146_000,1_045_000,-100,"LTE:1650:333","LTE PCI 333");check(restored.samples().get(5).breakBefore,"rotation resumes with explicit gap");
        history.clear();check(history.samples().isEmpty(),"SIM switch clears history");
        for(int i=0;i<65;i++)history.add(200_000+i*1_000,200_000+i*1_000,2_000_000+i*1_000,-100,"LTE","LTE PCI 1");
        check(history.samples().size()==60,"60 point cap");history.trim(564_001);check(history.samples().isEmpty(),"five-minute expiry");
        check(!SignalHistory.validSignal(0)&&!SignalHistory.validSignal(Integer.MAX_VALUE),"invalid values never become zero dBm");
        check(SignalHistory.strength(-85).equals("强")&&SignalHistory.strength(-115).equals("较弱")&&SignalHistory.strength(-116).equals("弱"),"reference threshold boundaries");
        check(SignalHistory.color(-125)==0xffcf5355&&SignalHistory.color(-80)==0xff177557,"gradient endpoints");
        System.out.println("SignalHistory: "+checks+" checks passed");
    }
}
