package com.chargehub.infrastructure;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowMessageTest {
    private static final String ORDER="00000000-0000-4000-8000-000000000001",EVENT="00000000-0000-4000-8000-000000000002";
    private WorkflowMessage valid(Map<String,Object> payload){return new WorkflowMessage(EVENT,"PaymentCaptured",1,"ORDER",ORDER,1,"trace-1","2026-10-18T07:00:00Z",payload);}
    @Test void canonicalTransportRoundTripsWithStableNestedHash(){
        var first=new LinkedHashMap<String,Object>();first.put("orderNo",ORDER);first.put("snapshot",Map.of("b",2,"a",1));
        var second=new LinkedHashMap<String,Object>();second.put("snapshot",new TreeMap<>(Map.of("a",1,"b",2)));second.put("orderNo",ORDER);
        var message=valid(first);
        assertEquals(message.hash(),valid(second).hash());
        assertEquals(message,WorkflowMessage.decode(message.encode()));
        assertEquals(64,message.hash().length());
    }
    @Test void unknownEventsAndNonCanonicalBusinessIdsAreRejectedBeforeDispatch(){
        assertThrows(IllegalArgumentException.class,()->new WorkflowMessage(EVENT,"RunShell",1,"ORDER",ORDER,1,"trace-1","2026-10-18T07:00:00Z",Map.of("orderNo",ORDER)));
        assertThrows(IllegalArgumentException.class,()->valid(Map.of("orderNo","1-1-1-1-1")));
        assertThrows(IllegalArgumentException.class,()->new WorkflowMessage(EVENT,"RefundRequested",1,"ORDER",ORDER,1,"trace-1","2026-10-18T07:00:00Z",Map.of("orderNo",ORDER)));
    }
    @Test void rejectsUnknownSchemaFractionalVersionExtraFieldsAndMalformedUtf8(){
        String body=new String(valid(Map.of("orderNo",ORDER)).encode(),StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,()->WorkflowMessage.decode(body.replace("\"schemaVersion\":1","\"schemaVersion\":2").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->WorkflowMessage.decode(body.replace("\"schemaVersion\":1","\"schemaVersion\":1.5").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->WorkflowMessage.decode(("{\"unexpected\":true,"+body.substring(1)).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->WorkflowMessage.decode(new byte[]{(byte)0xc3,(byte)0x28}));
        assertThrows(IllegalArgumentException.class,()->WorkflowMessage.decode(new byte[WorkflowMessage.MAX_BYTES+1]));
    }
    @Test void changingPayloadOrEnvelopeMetadataChangesIntegrityHash(){
        var original=valid(Map.of("orderNo",ORDER,"amountFen",10000));
        assertNotEquals(original.hash(),valid(Map.of("orderNo",ORDER,"amountFen",9999)).hash());
        var changed=new WorkflowMessage(EVENT,"PaymentCaptured",1,"ORDER",ORDER,2,"trace-1","2026-10-18T07:00:00Z",original.payload());
        assertNotEquals(original.hash(),changed.hash());
    }
    @Test void transportIsDerivedFromPersistedOutboxTimeAndOriginalFields(){
        var row=Map.<String,Object>of("event_id",EVENT,"event_name","PaymentCaptured","schema_version",1,"aggregate_type","ORDER","aggregate_id",ORDER,"aggregate_version",1,"trace_id","trace-1","created_at",Instant.parse("2026-10-18T07:00:00Z"),"payload",Json.write(Map.of("orderNo",ORDER)));
        assertEquals(valid(Map.of("orderNo",ORDER)),WorkflowMessage.fromOutbox(row));
    }
}
