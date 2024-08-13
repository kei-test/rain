package GInternational.server.api.dto;

import GInternational.server.api.vo.PaymentStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class AppleResultDTO {
    private LocalDateTime createdDate; // 게임을 한 시간
    private Long userId; // 게임을 한 유저
    private String rewardName; // 보상명
    private String rewardDescription; // 보상 세부 설명
    private String rewardValue; // 보상의 실제 값. 포인트의 경우 해당 포인트 수, 기프티콘의 경우 기프티콘의 종류
    private PaymentStatusEnum status; // 보상 지급 상태
    private LocalDateTime lastModifiedDate; // 결과값이 마지막으로 수정된 시간
}