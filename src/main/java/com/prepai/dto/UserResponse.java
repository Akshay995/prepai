package com.prepai.dto;

import com.prepai.model.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;



@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserResponse {
    public UUID id;
    public String name;
    public String email;
    public String avatarUrl;
    public User.Plan plan;
    public Integer credits;
    public Instant planExpiresAt;
    public long totalSessions;
    public Double avgScore;
}



