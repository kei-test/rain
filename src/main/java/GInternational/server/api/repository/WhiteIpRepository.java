package GInternational.server.api.repository;

import GInternational.server.api.entity.WhiteIp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WhiteIpRepository extends JpaRepository<WhiteIp, Long> {

    Optional<WhiteIp> findByWhiteIp(String whiteIp);
}
