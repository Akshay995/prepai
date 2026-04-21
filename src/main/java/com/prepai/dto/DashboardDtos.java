package com.prepai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class DashboardDtos {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StatsResponse {
        public long totalSessions;
        public long completedSessions;
        public Double avgScore;
        public Integer bestScore;
        public List<InterviewDtos.SessionListResponse> recentSessions;
    }
}
