package GInternational.server.api.service;

import GInternational.server.api.dto.RechargeProcessedRequestDTO;
import GInternational.server.api.dto.RechargeRequestDTO;
import GInternational.server.api.entity.*;
import GInternational.server.api.mapper.RechargeRequestMapper;
import GInternational.server.api.mapper.RechargeResponseMapper;
import GInternational.server.api.repository.*;
import GInternational.server.api.utilities.AuditContext;
import GInternational.server.api.utilities.AuditContextHolder;
import GInternational.server.api.vo.MoneyLogCategoryEnum;
import GInternational.server.api.vo.PointLogCategoryEnum;
import GInternational.server.api.vo.TransactionEnum;
import GInternational.server.api.vo.TransactionGubunEnum;
import GInternational.server.common.exception.ExceptionCode;
import GInternational.server.common.exception.RestControllerException;
import GInternational.server.common.ipinfo.service.IpInfoService;
import GInternational.server.security.auth.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional(value = "clientServerTransactionManager")
@RequiredArgsConstructor
public class RechargeService {

    @Value("${secret.api-key}")
    private String secretApiKey;

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final RechargeTransactionRepository rechargeTransactionRepository;
    private final RechargeRequestMapper rechargeRequestMapper;
    private final RechargeResponseMapper rechargeResponseMapper;
    private final RouletteService rouletteService;
    private final CheckAttendanceService checkAttendanceService;
    private final LoginStatisticService loginStatisticService;
    private final MoneyLogService moneyLogService;
    private final PointLogService pointLogService;
    private final LevelBonusPointSettingRepository levelBonusPointSettingRepository;
    private final IpInfoService ipInfoService;
    private final AutoRechargeRepository autoRechargeRepository;
    private final WebSocketMessageRepository webSocketMessageRepository;
    private final AutoRechargeBankAccountRepository autoRechargeBankAccountRepository;

    public void rechargeSportsBalance(Long userId, Long walletId, RechargeRequestDTO rechargeRequestDTO, PrincipalDetails principalDetails, HttpServletRequest request) {
        User user = userRepository.findById(userId).orElseThrow(
                () -> new RestControllerException(ExceptionCode.USER_NOT_FOUND, "유저 정보 없음"));
        Wallet wallet = walletRepository.findById(walletId).orElseThrow(
                () -> new RestControllerException(ExceptionCode.WALLET_NOT_FOUND, "지갑 정보 없음"));
        String clientIp = ipInfoService.getClientIp(request);

        if (rechargeRequestDTO.getRechargeAmount() > 0) {
            // 오늘 날짜에 대한 첫 충전 여부 확인
            LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            LocalDateTime endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
            boolean isFirstRechargeToday = isFirstRecharge(user, startOfDay, endOfDay);

            // 보너스 설정 가져오기
            LevelBonusPointSetting bonusSetting = levelBonusPointSettingRepository.findByLv(user.getLv())
                    .orElseThrow(() -> new RestControllerException(ExceptionCode.DATA_NOT_FOUND));

            double bonusPercentage;
            if (isFirstRechargeToday) {
                // 첫 충전인 경우 항상 firstRecharge 보너스 적용
                bonusPercentage = bonusSetting.getFirstRecharge();
            } else if (bonusSetting.isBonusActive()) {
                // 첫 충전이 아니고, bonusActive가 true인 경우에만 todayRecharge 보너스 적용
                bonusPercentage = bonusSetting.getTodayRecharge();
            } else {
                // 그 외의 경우 보너스 없음
                bonusPercentage = 0;
            }

            int calculatedBonus = (int) (rechargeRequestDTO.getRechargeAmount() * (bonusPercentage / 100));

            // 요청 바디에 bonus 필드가 있는 경우 해당 값 사용
            if (rechargeRequestDTO.getBonus() != null) {
                calculatedBonus = rechargeRequestDTO.getBonus();
            }

            RechargeTransaction rechargeTransaction = RechargeTransaction.builder()
                    .rechargeAmount(rechargeRequestDTO.getRechargeAmount())
                    .remainingSportsBalance(wallet.getSportsBalance() + rechargeRequestDTO.getRechargeAmount())
                    .bonus(calculatedBonus)
                    .remainingPoint((int) (wallet.getPoint() + calculatedBonus))
                    .user(user)
                    .lv(user.getLv())
                    .ip(clientIp)
                    .phone(user.getPhone())
                    .username(user.getUsername())
                    .nickname(user.getNickname())
                    .ownerName(wallet.getOwnerName())
                    .gubun(TransactionGubunEnum.SPORTS)
                    .status(TransactionEnum.UNREAD)
                    .site("test")
                    .message("")
                    .chargedCount((int) wallet.getChargedCount())
                    .createdAt(LocalDateTime.now())
                    .isFirstRecharge(isFirstRechargeToday)
                    .build();
            rechargeTransactionRepository.save(rechargeTransaction);

            AutoRecharge autoRecharge = AutoRecharge.builder()
                    .userId(userId)
                    .username(user.getUsername())
                    .site("test")
                    .number(user.getWallet().getNumber())
                    .bankName(user.getWallet().getBankName())
                    .ownerName(user.getWallet().getOwnerName())
                    .status("문자 미수신")
                    .createdAt(LocalDateTime.now())
                    .build();
            autoRechargeRepository.save(autoRecharge);

            rechargeResponseMapper.toDto(wallet);
        } else {
            throw new RestControllerException(ExceptionCode.INSUFFICIENT_FUNDS_OR_INVALID_AMOUNT, "충전 금액이 0보다 커야 합니다.");
        }
    }

    /**
     * 관리자에 의해 충전 요청의 상태를 대기중으로 변경.
     *
     * @param request 사용자의 HTTP 요청 정보
     * @param transactionId 상태를 변경할 충전 요청 ID
     * @param principalDetails 인증된 사용자 정보
     * @throws RestControllerException 충전 요청이 존재하지 않을 경우 예외 발생
     */
    @AuditLogService.Audit("충전 상태값 변경")
    public void updateRechargeTransactionStatusToWaiting(HttpServletRequest request, Long transactionId, PrincipalDetails principalDetails) {
        RechargeTransaction rechargeTransaction = rechargeTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new RestControllerException(ExceptionCode.APPLICATION_NOT_FOUND, "내역 없음"));

        if (rechargeTransaction.getStatus() != TransactionEnum.UNREAD) {
            throw new RestControllerException(ExceptionCode.ONLY_WAITING_TRANSACTIONS_CAN_BE_APPROVED, "대기 중인 상태의 신청 건만 승인 가능합니다.");
        }

        rechargeTransaction.setStatus(TransactionEnum.WAITING);

        AuditContext context = AuditContextHolder.getContext();
        String clientIp = request.getRemoteAddr();
        context.setIp(clientIp);
        context.setTargetId(String.valueOf(rechargeTransaction.getUser().getId()));
        context.setUsername(rechargeTransaction.getUser().getUsername());
        context.setDetails(rechargeTransaction.getUsername() + "의 " + rechargeTransaction.getRechargeAmount() + "원 충전요청건 상태값 " + rechargeTransaction.getStatus() + "으로 변경");
        context.setAdminUsername(principalDetails.getUsername());
        context.setTimestamp(LocalDateTime.now());

        rechargeTransactionRepository.save(rechargeTransaction);
    }

    /**
     * 충전 요청에 대한 승인 처리를 수행.
     *
     * @param request 사용자의 HTTP 요청 정보
     * @param transactionIds 승인할 충전 요청 ID 목록
     * @param rechargeProcessedRequestDTO 승인 처리에 필요한 추가 정보
     * @param principalDetails 인증된 사용자 정보
     * @throws RestControllerException 충전 요청이 존재하지 않거나 금액 정보가 없을 경우 예외 발생
     */
    @AuditLogService.Audit("충전 승인")
    public void updateRechargeSportsBalance(HttpServletRequest request, List<Long> transactionIds, RechargeProcessedRequestDTO rechargeProcessedRequestDTO, PrincipalDetails principalDetails) {

        for (Long transactionId : transactionIds) {
            RechargeTransaction originalRechargeTransaction = rechargeTransactionRepository.findById(transactionId)
                    .orElseThrow(() -> new RestControllerException(ExceptionCode.APPLICATION_NOT_FOUND, "내역 없음"));
            User user = originalRechargeTransaction.getUser();
            Wallet wallet = walletRepository.findById(user.getWallet().getId())
                    .orElseThrow(() -> new RestControllerException(ExceptionCode.WALLET_INFO_NOT_FOUND, "금액 정보 없음"));

            if (originalRechargeTransaction.getStatus() == TransactionEnum.WAITING) {
                int bonusValue = originalRechargeTransaction.getBonus();
                LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
                LocalDateTime endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
                boolean isFirstRecharge = isFirstRecharge(user, startOfDay, endOfDay);

                // 요청 바디에 bonus 필드가 있는 경우 해당 값 사용
                if (rechargeProcessedRequestDTO.getBonus() != null) {
                    bonusValue = rechargeProcessedRequestDTO.getBonus();
                }
                AuditContext context = AuditContextHolder.getContext();
                String clientIp = request.getRemoteAddr();
                context.setIp(clientIp);
                context.setTargetId(String.valueOf(user.getId()));
                context.setUsername(user.getUsername());
                context.setDetails(user.getUsername() + "의 " + originalRechargeTransaction.getRechargeAmount() + "원 충전요청 승인");
                context.setAdminUsername(principalDetails.getUsername());
                context.setTimestamp(LocalDateTime.now());

                RechargeTransaction updatedRechargeTransaction = RechargeTransaction.builder()
                        .id(originalRechargeTransaction.getId())
                        .rechargeAmount(originalRechargeTransaction.getRechargeAmount())
                        .remainingSportsBalance(wallet.getSportsBalance() + originalRechargeTransaction.getRechargeAmount())
                        .ownerName(wallet.getOwnerName())
                        .user(user)
                        .lv(user.getLv())
                        .ip(originalRechargeTransaction.getIp())
                        .phone(user.getPhone())
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .processedAt(LocalDateTime.now())
                        .gubun(TransactionGubunEnum.SPORTS)
                        .status(TransactionEnum.APPROVAL)
                        .bonus(bonusValue)
                        .site("test")
                        .message("")
                        .remainingPoint((int) (wallet.getPoint() + bonusValue))
                        .chargedCount((int) (user.getWallet().getChargedCount() + 1))
                        .isFirstRecharge(isFirstRecharge)
                        .build();

                rechargeTransactionRepository.save(updatedRechargeTransaction);


                long rechargeAmount = updatedRechargeTransaction.getRechargeAmount();
                long bonusAmount = updatedRechargeTransaction.getBonus();

                wallet.setSportsBalance(wallet.getSportsBalance() + rechargeAmount);
                wallet.setPoint(wallet.getPoint() + bonusAmount);
                wallet.setChargedCount(updatedRechargeTransaction.getChargedCount());
                wallet.setLastRechargedAt(updatedRechargeTransaction.getProcessedAt());
                wallet.setDepositTotal(wallet.getDepositTotal() + rechargeAmount);
                wallet.setTotalSettlement(wallet.getDepositTotal() - wallet.getWithdrawTotal());
                wallet.setChargedCount(updatedRechargeTransaction.getChargedCount());

                if ("ROLE_USER".equals(originalRechargeTransaction.getUser().getRole())) {
                    loginStatisticService.recordRecharge();
                }

                walletRepository.save(wallet);
                userRepository.save(user);

                moneyLogService.recordMoneyUsage(user.getId(), rechargeAmount, wallet.getSportsBalance(), MoneyLogCategoryEnum.충전, "");
                pointLogService.recordPointLog(user.getId(), bonusAmount, PointLogCategoryEnum.충전, originalRechargeTransaction.getIp(), "");
                rouletteService.bonusRouletteSpinForRecharge(user.getId(), new BigDecimal(rechargeAmount));
                checkAttendanceService.chargeAndCheckAttendance(user.getId(), new BigDecimal(rechargeAmount));
            } else {
                throw new RuntimeException("입금 대기중인 내역만 승인가능합니다.");
            }
        }
    }

    // 자동충전 승인
    public void autoApproval(Long transactionId, Long autoId, String apiKey, String message, String depositor, String amount, LocalDateTime dateTime) {

        if (!secretApiKey.equals(apiKey)) {
            throw new RestControllerException(ExceptionCode.UNAUTHORIZED_ACCESS, "Invalid API Key");
        }

        RechargeTransaction rechargeTransaction = rechargeTransactionRepository.findById(transactionId).orElseThrow
                (() -> new RestControllerException(ExceptionCode.DATA_NOT_FOUND, "신청 내역이 없습니다."));
        AutoRecharge autoRecharge = autoRechargeRepository.findById(autoId).orElseThrow
                (() -> new RestControllerException(ExceptionCode.DATA_NOT_FOUND, "자동충전 내역이 없습니다."));
        User user = userRepository.findById(rechargeTransaction.getUser().getId()).orElseThrow
                (() -> new RestControllerException(ExceptionCode.USER_NOT_FOUND, "유저 정보 없음"));
        Wallet wallet = walletRepository.findById(user.getWallet().getId()).orElseThrow
                (() -> new RestControllerException(ExceptionCode.WALLET_INFO_NOT_FOUND, "금액 정보 없음"));

        if (!(rechargeTransaction.getStatus() == TransactionEnum.CANCELLATION)) {
            RechargeTransaction updatedRechargeTransaction = RechargeTransaction.builder()
                    .id(rechargeTransaction.getId())
                    .rechargeAmount(rechargeTransaction.getRechargeAmount())
                    .remainingSportsBalance(wallet.getSportsBalance() + rechargeTransaction.getRechargeAmount())
                    .ownerName(wallet.getOwnerName())
                    .user(user)
                    .lv(user.getLv())
                    .ip(rechargeTransaction.getIp())
                    .phone(user.getPhone())
                    .username(user.getUsername())
                    .nickname(user.getNickname())
                    .processedAt(LocalDateTime.now())
                    .gubun(TransactionGubunEnum.SPORTS)
                    .status(TransactionEnum.AUTO_APPROVAL)
                    .bonus(rechargeTransaction.getBonus())
                    .site("test")
                    .message("")
                    .remainingPoint((int) (wallet.getPoint() + rechargeTransaction.getBonus()))
                    .chargedCount((int) (user.getWallet().getChargedCount() + 1))
                    .isFirstRecharge(rechargeTransaction.isFirstRecharge())
                    .depositor(depositor)
                    .build();

            rechargeTransactionRepository.save(updatedRechargeTransaction);

            long rechargeAmount = updatedRechargeTransaction.getRechargeAmount();
            long bonusAmount = updatedRechargeTransaction.getBonus();

            wallet.setSportsBalance(wallet.getSportsBalance() + rechargeAmount);
            wallet.setPoint(wallet.getPoint() + bonusAmount);
            wallet.setChargedCount(updatedRechargeTransaction.getChargedCount());
            wallet.setLastRechargedAt(updatedRechargeTransaction.getProcessedAt());
            wallet.setDepositTotal(wallet.getDepositTotal() + rechargeAmount);
            wallet.setTotalSettlement(wallet.getDepositTotal() - wallet.getWithdrawTotal());
            wallet.setChargedCount(updatedRechargeTransaction.getChargedCount());

            if ("ROLE_USER".equals(rechargeTransaction.getUser().getRole())) {
                loginStatisticService.recordRecharge();
            }

            walletRepository.save(wallet);
            userRepository.save(user);

            moneyLogService.recordMoneyUsage(user.getId(), rechargeAmount, wallet.getSportsBalance(), MoneyLogCategoryEnum.자동충전, "");
            pointLogService.recordPointLog(user.getId(), bonusAmount, PointLogCategoryEnum.자동충전, rechargeTransaction.getIp(), "");
            rouletteService.bonusRouletteSpinForRecharge(user.getId(), new BigDecimal(rechargeAmount));
            checkAttendanceService.chargeAndCheckAttendance(user.getId(), new BigDecimal(rechargeAmount));

            autoRecharge.setTimestamp(dateTime);
            autoRecharge.setMessage(message);
            autoRecharge.setDepositor(depositor);
            autoRecharge.setAmount(amount);
            autoRecharge.setUpdatedAt(LocalDateTime.now());
            autoRecharge.setStatus("문자 수신");
            autoRechargeRepository.save(autoRecharge);
        } else {
            throw new RuntimeException("취소된 신청건은 승인할 수 없습니다.");
        }
    }

    public boolean autoApprovalBasedOnMessage(String amount, String depositor, String message, LocalDateTime dateTime) {
        List<RechargeTransaction> transactions = rechargeTransactionRepository
                .findAllByStatusInAndCreatedAtAfter(
                        Arrays.asList(TransactionEnum.WAITING, TransactionEnum.UNREAD),
                        LocalDateTime.now().minusMinutes(30)
                );

        // 예금주와 충전 금액이 일치하는 트랜잭션 리스트
        List<RechargeTransaction> matchingTransactions = transactions.stream()
                .filter(transaction -> String.valueOf(transaction.getRechargeAmount()).equals(amount) && transaction.getOwnerName().equals(depositor))
                .collect(Collectors.toList());

        // 일치하는 트랜잭션이 2개 이상인 경우 로그를 남기고 처리하지 않음
        if (matchingTransactions.size() > 1) {
            System.out.println("예금주와 충전금액이 일치하는 신청건이 2개 이상이므로 자동충전 신청처리를 하지 않았습니다.");
            return false;
        }

        // 일치하는 트랜잭션이 정확히 1개인 경우 계좌번호 검증 후 승인 처리
        if (matchingTransactions.size() == 1) {
            RechargeTransaction transaction = matchingTransactions.get(0);

            // 본문에서 계좌번호 포함 여부 검증
            List<AutoRechargeBankAccount> bankAccounts = autoRechargeBankAccountRepository.findAll();
            boolean accountMatched = false;
            for (AutoRechargeBankAccount account : bankAccounts) {
                boolean isAccountInMessage = message.contains(account.getNumber());
                System.out.println("계좌번호: " + account.getNumber() + ", 사용여부: " + account.getIsUse() + ", 본문에 포함: " + isAccountInMessage);
                if (isAccountInMessage && account.getIsUse()) {
                    accountMatched = true;
                    break;
                }
            }

            if (accountMatched) {
                LocalDateTime startDateTime = transaction.getCreatedAt().minusSeconds(1);
                LocalDateTime endDateTime = transaction.getCreatedAt().plusSeconds(1);

                AutoRecharge autoRecharge = autoRechargeRepository.findByUserIdAndCreatedAtBetween(transaction.getUser().getId(), startDateTime, endDateTime).orElseThrow
                        (() -> new RestControllerException(ExceptionCode.DATA_NOT_FOUND, "자동충전 내역이 없습니다."));
                autoApproval(transaction.getId(), autoRecharge.getId(), secretApiKey, message, depositor, amount, dateTime);
                System.out.println("자동충전 승인 처리가 성공적으로 완료되었습니다.");
                return true;
            } else {
                System.out.println("본문에 일치하는 계좌번호가 없거나 계좌가 사용 중지 상태입니다.");
                return false;
            }
        }

        // 일치하는 트랜잭션이 없는 경우
        System.out.println("일치하는 트랜잭션이 없습니다.");
        return false;
    }



    /**
     * 충전 요청에 대한 거절(취소).
     *
     * @param request 사용자의 HTTP 요청 정보
     * @param userId 거절 처리할 사용자 ID
     * @param transactionIds 거절할 충전 요청 ID 목록
     * @param principalDetails 인증된 사용자 정보
     * @throws RestControllerException 충전 요청이 존재하지 않을 경우 예외 발생
     */
    @AuditLogService.Audit("충전 취소")
    public void cancelRechargeTransaction(HttpServletRequest request, Long userId, List<Long> transactionIds, PrincipalDetails principalDetails) {
        for (Long transactionId : transactionIds) {

            User user = userRepository.findById(userId).orElseThrow
                    (()-> new RestControllerException(ExceptionCode.USER_NOT_FOUND, "유저 정보 없음"));
            RechargeTransaction rechargeTransaction = rechargeTransactionRepository.findById(transactionId).orElseThrow
                    (() -> new RestControllerException(ExceptionCode.APPLICATION_NOT_FOUND, "내역 없음"));

            if (rechargeTransaction.getStatus() == TransactionEnum.WAITING || rechargeTransaction.getStatus() == TransactionEnum.UNREAD) {

                AuditContext context = AuditContextHolder.getContext();
                String clientIp = request.getRemoteAddr();
                context.setIp(clientIp);
                context.setTargetId(String.valueOf(user.getId()));
                context.setUsername(user.getUsername());
                context.setDetails(user.getUsername() + "의 " + rechargeTransaction.getRechargeAmount() + "원 충전요청 취소");
                context.setAdminUsername(principalDetails.getUsername());
                context.setTimestamp(LocalDateTime.now());

                rechargeTransaction = RechargeTransaction.builder()
                        .id(rechargeTransaction.getId())
                        .rechargeAmount(rechargeTransaction.getRechargeAmount())
                        .bonus(rechargeTransaction.getBonus())
                        .user(user)
                        .lv(user.getLv())
                        .ip(user.getIp())
                        .phone(user.getPhone())
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .ownerName(user.getWallet().getOwnerName())
                        .gubun(TransactionGubunEnum.SPORTS)
                        .site("test")
                        .status(TransactionEnum.CANCELLATION)
                        .processedAt(LocalDateTime.now())
                        .build();

                RechargeTransaction savedRechargeTransaction = rechargeTransactionRepository.save(rechargeTransaction);
            }
        }
    }

    /**
     * 매일 자정에 모든 지갑의 오늘 충전 횟수를 0으로 초기화하는 스케줄링 메서드.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void resetTodayChargedCount() {
        List<Wallet> wallets = walletRepository.findAll();
        for (Wallet wallet : wallets) {
            wallet.setTodayChargedCount(0);
            walletRepository.save(wallet);
        }
    }

    /**
     * 해당 사용자의 첫 충전 여부를 확인.
     *
     * @param user 사용자 엔티티
     * @param startOfDay 오늘의 시작 시간
     * @param endOfDay 오늘의 종료 시간
     * @return 첫 충전 여부
     */
    private boolean isFirstRecharge(User user, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return !rechargeTransactionRepository.existsByUserAndStatusAndProcessedAtBetween(
                user,
                TransactionEnum.APPROVAL,
                startOfDay,
                endOfDay
        );
    }

    /**
     * 주어진 조건에 따라 처리되지 않은 거래들의 상태를 업데이트.
     */
    @Scheduled(fixedDelay = 1800000) // 30분 마다 상태 체크 후 상태값 업데이트
    public void unProcessedTransactions() {
        List<RechargeTransaction> unprocessedTransaction = getWaitStatusAutoTransactions();
        updateStatusToTimeout(unprocessedTransaction);
    }

    /**
     * 처리되지 않은 거래들을 조회.
     *
     * @return 읽지않음 또는 대기 상태인 거래 목록
     */
    private List<RechargeTransaction> getWaitStatusAutoTransactions() {
        return rechargeTransactionRepository.searchByRechargeTransactionCondition();
    }

    /**
     * 읽지않음 또는 대기 상태인 거래들의 상태를 타임아웃으로 업데이트.
     *
     * @param rechargeTransactions 타임아웃으로 변경할 자동 거래 목록
     */
    private void updateStatusToTimeout(List<RechargeTransaction> rechargeTransactions) {
        for (RechargeTransaction rechargeTransaction : rechargeTransactions) {
            rechargeTransaction.setStatus(TransactionEnum.TIMEOUT);
            rechargeTransactionRepository.save(rechargeTransaction);
        }
    }
}
