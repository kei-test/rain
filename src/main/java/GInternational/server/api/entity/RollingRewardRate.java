package GInternational.server.api.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import java.math.BigDecimal;

@NoArgsConstructor
@Getter
@Setter
@Entity(name = "rolling_reward_rate")
public class RollingRewardRate {

    @Id
    private Integer level;

    @Column(precision = 19, scale = 4)
    private BigDecimal rate;
}