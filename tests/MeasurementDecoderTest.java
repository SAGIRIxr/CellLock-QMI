package com.celllock.app;

import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** 合成边界包与可选本地真机回包；不连接设备、不请求 Root。 */
public final class MeasurementDecoderTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejected(Action action) throws Exception {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid packet accepted");
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 255));
        return text.toString();
    }
    private static JSONObject record(int service, int message, int slot, JSONObject tlvs) throws Exception {
        return new JSONObject().put("service", service).put("message", message).put("slot", slot)
                .put("transport", 0).put("result", 0).put("error", 0).put("tlvs", tlvs);
    }
    private static String nas(JSONObject tlvs) throws Exception {
        return record(3, 0x45, 0, new JSONObject()) + "\n" + record(3, 0x43, 0, tlvs);
    }
    private static byte[] nr() {
        ByteBuffer b = buffer(92);
        b.putShort((short)92).putShort((short)0xb97f).putLong(0);
        b.putShort((short)7).putShort((short)2).put((byte)1);
        b.position(28);
        b.putInt(504990).put((byte)2).put((byte)1).putShort((short)48);
        b.position(60);
        for (int pci : new int[]{48,49})
            b.putShort((short)pci).putShort((short)0).putInt(0).putInt(-100 * 128).putInt(0);
        return b.array();
    }
    private static void observation(RadioCell cell) {
        check(!cell.mapped && !cell.hasTarget() && cell.timestamp == 0, "unknown mapping/time is observation only");
    }
    public static void main(String[] args) throws Exception {
        byte[] packet = nr();
        List<RadioCell> cells = MeasurementDecoder.nr(packet);
        check(cells.size()==2 && cells.get(0).arfcn==504990 && cells.get(1).pci==49, "NR identities");
        check(cells.get(0).rsrp==-100 && !cells.get(0).registered, "NR Q7, no guessed serving role");
        for (RadioCell cell : cells) observation(cell);
        byte[] next = packet.clone();
        ByteBuffer later = ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN);
        later.putShort(60, (short)49).putInt(68, -105 * 128).putShort(76, (short)50);
        JSONObject batch = new JSONObject().put("logs", new JSONArray().put(hex(packet)).put(hex(next)));
        cells = MeasurementDecoder.nrBatch(batch);
        check(cells.size() == 3 && cells.stream().anyMatch(c -> c.pci == 48), "retain neighbor absent from last packet");
        check(cells.stream().anyMatch(c -> c.pci == 49 && c.rsrp == -105), "latest reading replaces duplicate");
        for (RadioCell cell : cells) observation(cell);
        check(MeasurementDecoder.nrBatch(new JSONObject(batch.toString()).put("error", "cleanup")).isEmpty(), "discard unclean batch");
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 33; i++) tooMany.put(hex(packet));
        rejected(() -> MeasurementDecoder.nrBatch(new JSONObject().put("logs", tooMany)));
        byte[] unknown = next.clone(); unknown[12] = 9;
        rejected(() -> MeasurementDecoder.nrBatch(new JSONObject().put("logs", new JSONArray().put(hex(packet)).put(hex(unknown)))));
        for (int length=0; length<packet.length; length++) {
            final byte[] truncated=Arrays.copyOf(packet,length);
            rejected(()->MeasurementDecoder.nr(truncated));
        }
        byte[] version=packet.clone(); version[12]=9;
        rejected(()->MeasurementDecoder.nr(version));
        byte[] pci=packet.clone(); pci[60]=(byte)0xf0; pci[61]=3;
        rejected(()->MeasurementDecoder.nr(pci));
        byte[] beams=packet.clone(); beams[64]=65;
        rejected(()->MeasurementDecoder.nr(beams));
        byte[] extra=Arrays.copyOf(packet,packet.length+1); extra[0]=(byte)extra.length;
        rejected(()->MeasurementDecoder.nr(extra));
        rejected(()->MeasurementDecoder.hex("0"));
        rejected(()->MeasurementDecoder.hex("zz"));

        ByteBuffer lte=buffer(29); lte.position(10);
        lte.putShort((short)1650).putShort((short)321).putInt(0).put((byte)1);
        lte.putShort((short)321).putShort((short)-100).putShort((short)-980).putInt(0);
        JSONObject same=new JSONObject().put("13",hex(lte.array()));
        cells=MeasurementDecoder.nas(nas(same),0);
        check(cells.size()==1 && cells.get(0).arfcn==1650 && cells.get(0).rsrp==-98 && cells.get(0).registered,"LTE same-frequency");
        observation(cells.get(0));
        same.put("27",hex(buffer(4).putInt(70000).array()));
        check(MeasurementDecoder.nas(nas(same),0).get(0).arfcn==70000,"extended EARFCN");
        ByteBuffer other=buffer(18); other.put((byte)0).put((byte)1).putShort((short)1300)
                .put(new byte[3]).put((byte)1).putShort((short)400).putShort((short)-100).putShort((short)-1100).putInt(0);
        JSONObject inter=new JSONObject().put("14",hex(other.array()));
        check(MeasurementDecoder.nas(nas(inter),0).get(0).pci==400,"LTE inter-frequency");
        inter.put("28","00"); rejected(()->MeasurementDecoder.nas(nas(inter),0));
        JSONObject frequency=new JSONObject().put("2e",hex(buffer(4).putInt(504990).array()));
        cells=MeasurementDecoder.nas(nas(frequency),0);
        check(cells.size()==1 && cells.get(0).arfcn==504990 && !cells.get(0).registered
                && !cells.get(0).hasTarget() && !cells.get(0).hasSignal(),"frequency-only observation retained without invented cell identity");
        ByteBuffer identity=buffer(22); identity.position(14);
        identity.putShort((short)48).putShort((short)-100).putShort((short)-990).putShort((short)50);
        frequency.put("2f",hex(identity.array()));
        cells=MeasurementDecoder.nas(nas(frequency),0);
        check(cells.size()==1 && cells.get(0).nr && cells.get(0).pci==48 && cells.get(0).rsrp==-99,"complete NR identity");
        check(cells.get(0).details.get("RSRQ").equals("-10.0 dB") && cells.get(0).details.get("SNR").equals("5.0 dB"), "NAS NR keeps quality fields");
        observation(cells.get(0));
        check(MeasurementDecoder.nas(nas(frequency),1).isEmpty(),"must not cross slots");
        check(MeasurementDecoder.nas(record(3,0x43,0,frequency).toString(),0).isEmpty(),"bind required");
        JSONObject failed=record(3,0x45,0,new JSONObject()).put("error",1);
        check(MeasurementDecoder.nas(failed+"\n"+record(3,0x43,0,frequency),0).isEmpty(),"failed bind rejected");
        JSONObject shortLte=new JSONObject().put("13",hex(Arrays.copyOf(lte.array(),28)));
        rejected(()->MeasurementDecoder.nas(nas(shortLte),0));

        ByteBuffer slots=buffer(21); slots.put((byte)2);
        for(int i=0;i<2;i++)slots.putInt(2).putInt(1).put((byte)(i+1)).put((byte)0);
        JSONObject uim=record(11,0x47,-1,new JSONObject().put("10",hex(slots.array())));
        List<SimInfo> sims=MeasurementDecoder.uim(uim.toString());
        check(sims.size()==2 && sims.get(0).slot==0 && sims.get(1).slot==1,"physical slots");
        check(!sims.get(0).trusted() && sims.get(0).subscriptionId==-1,"UIM cannot invent subscription");
        check(new SimInfo(4,0,"", "Root 电话").trusted(),"real subscription can map slot");
        rejected(()->new SimInfo(-2,0,"", "")); rejected(()->new SimInfo(4,8,"", ""));
        check(!RadioCell.validIdentity(true,Integer.MAX_VALUE,Integer.MAX_VALUE),"unavailable identity rejected");
        JSONObject model=new JSONObject().put("nr",false).put("registered",true).put("arfcn",1650).put("pci",321).put("timestamp",999999);
        check(RadioCell.parse(model,"Root 电话").hasTarget(),"fresh mapped target");
        model.put("timestamp",900000);check(!RadioCell.parse(model,"Root 电话").hasTarget(),"stale target rejected");
        model.put("timestamp",1000001);check(!RadioCell.parse(model,"Root 电话").hasTarget(),"future target rejected");
        model.put("timestamp",0);check(!RadioCell.parse(model,"Root 电话").hasTarget(),"unknown time rejected");

        byte[] missingPci=packet.clone();
        ByteBuffer.wrap(missingPci).order(ByteOrder.LITTLE_ENDIAN).putShort(60, (short)65535).putInt(72, -12 * 128);
        cells=MeasurementDecoder.nr(missingPci);
        check(cells.size()==2 && cells.get(0).pci==Integer.MAX_VALUE && cells.get(0).rsrp==-100, "DIAG unknown PCI keeps other valid measurements");
        check(cells.get(0).details.get("RSRQ").equals("-12.0 dB"), "DIAG RSRQ Q7 decoded");
        byte[] missingCarrierPci=packet.clone();
        ByteBuffer.wrap(missingCarrierPci).order(ByteOrder.LITTLE_ENDIAN).putShort(34, (short)65535);
        check(MeasurementDecoder.nr(missingCarrierPci).size()==2, "unknown carrier PCI does not discard valid neighbors");
        ByteBuffer.wrap(identity.array()).order(ByteOrder.LITTLE_ENDIAN).putShort(14, (short)65535);
        JSONObject partialNr=new JSONObject().put("2e", frequency.getString("2e")).put("2f",hex(identity.array()));
        cells=MeasurementDecoder.nas(nas(partialNr),0);
        check(cells.size()==1 && cells.get(0).pci==Integer.MAX_VALUE && cells.get(0).arfcn==504990 && cells.get(0).rsrp==-99,
                "NAS unknown PCI retains frequency and signal");
        partialNr.remove("2e");
        check(MeasurementDecoder.nas(nas(partialNr),0).get(0).details.containsKey("SNR"), "NAS missing frequency retains quality");

        ByteBuffer networkLte = buffer(39);
        networkLte.put((byte)0).put(new byte[]{0x64,(byte)0xf0,0x10}).putShort((short)0x1234).putInt(0x01234567)
                .putShort((short)1650).putShort((short)321).putInt(0).put((byte)2);
        for (int p : new int[]{321,322}) networkLte.putShort((short)p).putShort((short)-100).putShort((short)-980).putInt(0);
        JSONObject network = new JSONObject().put("13",hex(networkLte.array()));
        cells = MeasurementDecoder.nas(nas(network),0);
        check(cells.get(0).identity == 0x01234567 && cells.get(0).details.get("TAC").equals("4660")
                && cells.get(0).details.get("PLMN").equals("46001"), "LTE serving network identity decoded");
        check(cells.get(1).identity == -1 && !cells.get(1).details.containsKey("TAC")
                && !cells.get(1).details.containsKey("PLMN"), "LTE neighbors do not inherit serving identity");
        byte[] noMeasurements = Arrays.copyOf(networkLte.array(),19); noMeasurements[18]=0;
        cells = MeasurementDecoder.nas(nas(new JSONObject().put("13",hex(noMeasurements))),0);
        check(cells.size()==1 && cells.get(0).identity==0x01234567 && !cells.get(0).hasSignal(), "LTE header retained without measurements");
        ByteBuffer networkNr = buffer(22);
        networkNr.put(new byte[]{0x64,(byte)0xf0,0x10,0x12,0x34,0x56}).putLong(0x123456789L)
                .putShort((short)65535).putShort((short)-100).putShort((short)-990).putShort((short)50);
        network = new JSONObject().put("2e",hex(buffer(4).putInt(640000).array())).put("2f",hex(networkNr.array()));
        cells = MeasurementDecoder.nas(nas(network),0);
        check(cells.get(0).identity==0x123456789L && cells.get(0).pci==Integer.MAX_VALUE
                && cells.get(0).details.get("TAC").equals("1193046") && cells.get(0).details.get("PLMN").equals("46001"),
                "NR little-endian NCI and big-endian TAC survive unavailable PCI");
        networkNr.put(0,(byte)0xab).put(3,(byte)255).put(4,(byte)255).put(5,(byte)255).putLong(6,-1L);
        network.put("2f",hex(networkNr.array())); cells = MeasurementDecoder.nas(nas(network),0);
        check(cells.get(0).identity==-1 && !cells.get(0).details.containsKey("PLMN") && !cells.get(0).details.containsKey("TAC")
                && cells.get(0).rsrp==-99, "invalid network identifiers do not discard valid signal");
        networkNr.put(0,(byte)0x21).put(1,(byte)0x43).put(2,(byte)0x65);
        network.put("2f",hex(networkNr.array())); cells = MeasurementDecoder.nas(nas(network),0);
        check(cells.get(0).details.get("PLMN").equals("123456"), "six BCD digits retained without guessed MCC/MNC split");
        check(RadioCell.parse(cells.get(0).json(),"Root 电话").details.equals(cells.get(0).details), "NAS details survive JSON boundary");

        if(args.length==1){
            Path directory=Path.of(args[0]);
            try(var paths=Files.list(directory)){
                for(Path path:paths.filter(p->p.getFileName().toString().startsWith("qmi-")&&p.toString().endsWith(".jsonl")).toList()){
                    String raw=Files.readString(path);
                    check(MeasurementDecoder.uim(raw).size()==2,"captured two slots: "+path.getFileName());
                    for(int slot=0;slot<2;slot++){
                        List<RadioCell> captured=MeasurementDecoder.nas(raw,slot);
                        if(path.getFileName().toString().equals("qmi-location-off.jsonl"))
                            check(captured.isEmpty(),"old location-gated probe contains only UIM");
                        else check(!captured.isEmpty(),"captured NAS cells: "+path.getFileName()+"/"+slot);
                        for(RadioCell cell:captured)observation(cell);
                    }
                }
            }
            byte[] capture=Files.readAllBytes(directory.resolve("nr-fine-denied.bin"));
            int at=0, logs=0;
            while(at<capture.length){
                int length=ByteBuffer.wrap(capture,at,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                at+=4;
                check(length>=12 && length<=capture.length-at,"capture envelope length");
                List<RadioCell> captured=MeasurementDecoder.nr(Arrays.copyOfRange(capture,at,at+length));
                check(!captured.isEmpty(),"captured NR cells");
                for(RadioCell cell:captured)observation(cell);
                at+=length;logs++;
            }
            check(at==capture.length&&logs==30,"all 30 captured NR logs decoded");
        }
        System.out.println("MeasurementDecoder: "+checks+" checks passed");
    }
}
