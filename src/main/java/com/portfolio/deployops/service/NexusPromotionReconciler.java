package com.portfolio.deployops.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NexusPromotionReconciler {
    private final NexusPromotionService service;
    private final NexusPromotionJobGateway gateway;
    @Scheduled(fixedDelayString="${deployops.nexus-backup.poll-ms:5000}") public void poll(){
        for(var request:service.unfinished())try{
            if(!request.isSubmitted()&&service.markSubmitted(request.getId()))gateway.submit(request.getId(),request.getRestoreRequestId());
            var result=gateway.result(request.getId(),request.getRestoreRequestId());
            if(result!=null)service.accept(request.getId(),result);
        }catch(Exception ambiguous){try{service.uncertain(request.getId());}catch(Exception unavailable){/* 선점을 보존하며 자동 재게시하지 않는다. */}}
    }
}
