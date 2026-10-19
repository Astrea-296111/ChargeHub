package com.chargehub.reconcile;

import com.chargehub.identity.Actor;
import com.chargehub.infrastructure.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Finance-only HTTP boundary. Every financial write carries a reason and durable idempotency. */
@RestController
@RequestMapping("/v1/admin")
@PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
public class FinanceOperationsController {
    private final FinanceRecoveryService recovery;private final ReconciliationService reconciliation;
    public FinanceOperationsController(FinanceRecoveryService recovery,ReconciliationService reconciliation){this.recovery=recovery;this.reconciliation=reconciliation;}
    public record Reason(@NotBlank @Size(max=255) String reason){}
    public record Disposition(@NotBlank @Pattern(regexp="KEEP_OPEN|CONFIRM_SUPERSEDED") String action,@Positive Long replacementBatchId,@NotBlank @Size(max=255) String reason){}
    @PostMapping(value="/reconciliations",consumes="multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse importCsv(@RequestParam MultipartFile file,@RequestParam String channel,@RequestParam String businessDate,
                                 @RequestParam String billType,@RequestParam String sourceTimezone,@RequestParam int versionNo,
                                 @RequestParam(defaultValue="财务导入渠道账单") String reason,@RequestHeader("Idempotency-Key") String key)throws java.io.IOException{
        if(file.getSize()>ChannelCsv.MAX_BYTES)throw new BusinessException(413,"FILE_TOO_LARGE","渠道文件超过10MiB");
        String csv;
        try{csv=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();}
        catch(CharacterCodingException error){throw new BusinessException(400,"INVALID_CSV_ENCODING","渠道账单必须为有效UTF-8");}
        return ApiResponse.ok(recovery.importCsv(Actor.id(),key,channel,businessDate,billType,sourceTimezone,versionNo,csv,reason));
    }
    @GetMapping("/reconciliation-items")
    public ApiResponse items(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String status){return ApiResponse.ok(reconciliation.items(cursor,limit,status));}
    @GetMapping("/finance/reconciliation-items/{itemId}")
    public ApiResponse item(@PathVariable long itemId){return ApiResponse.ok(recovery.item(itemId));}
    @GetMapping("/finance/reconciliations/{batchId}")
    public ApiResponse batch(@PathVariable long batchId){return ApiResponse.ok(recovery.batch(batchId));}
    @PostMapping("/finance/reconciliations/{batchId}/recover")
    public ApiResponse recover(@PathVariable long batchId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Reason body){return ApiResponse.ok(recovery.recoverBatch(Actor.id(),batchId,key,body.reason));}
    @PostMapping("/finance/reconciliation-items/{itemId}/disposition")
    public ApiResponse disposition(@PathVariable long itemId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Disposition body){return ApiResponse.ok(recovery.disposition(Actor.id(),itemId,key,body.action,body.replacementBatchId,body.reason));}
    @PostMapping({"/refunds/{refundNo}/retry","/finance/refunds/{refundNo}/retry"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse retry(@PathVariable String refundNo,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Reason body){return ApiResponse.ok(recovery.refund(Actor.id(),refundNo,key,body.reason,true));}
    @PostMapping("/finance/refunds/{refundNo}/recover")
    public ApiResponse recoverRefund(@PathVariable String refundNo,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Reason body){return ApiResponse.ok(recovery.refund(Actor.id(),refundNo,key,body.reason,false));}
}
