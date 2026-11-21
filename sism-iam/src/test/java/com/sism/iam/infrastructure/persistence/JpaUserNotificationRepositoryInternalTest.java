package com.sism.iam.infrastructure.persistence;

import com.sism.iam.domain.notification.UserNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-21 生产事故回归闸：
 * 仓库接口用「文本块 + 常量」拼接 JPQL 时行尾空格被清理（and upper → andupper），
 * 语法错误只在 Spring 上下文启动（仓库 bean 初始化）时暴露——此前 sism-iam 无
 * JPA 切片测试而漏网，坏镜像上线导致生产后端崩溃循环（v1.5.2 回滚事故）。
 *
 * 本类 = @DataJpaTest 上下文启动即解析全部 @Query（语法闸），并对 9 条拼接查询逐条验证行为。
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = com.sism.iam.TestApplication.class)
@DisplayName("用户通知仓库 JPQL 拼接回归（启动期语法闸 + 行为验证）")
class JpaUserNotificationRepositoryInternalTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JpaUserNotificationRepositoryInternal repository;

    private static final Long RECIPIENT = 9001L;
    private static final PageRequest PAGE = PageRequest.of(0, 10);

    @BeforeEach
    void seed() {
        repository.save(notification("APPROVAL_SUBMITTED", "您有新的审批任务", "战略部任务待您审批", "UNREAD"));
        repository.save(notification("APPROVAL_APPROVED", "审批已通过", "您的上报已通过审核", "READ"));
        repository.save(notification("REMINDER", "催办：请尽快填报", "您的指标尚未提交", "UNREAD"));
        repository.save(notification("SYSTEM", "系统公告", "平台将于今晚维护", "UNREAD"));
        entityManager.flush();
        entityManager.clear();
    }

    private static UserNotification notification(String type, String title, String content, String status) {
        UserNotification n = new UserNotification();
        n.setRecipientUserId(RECIPIENT);
        n.setNotificationType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setStatus(status);
        return n;
    }

    @Test
    @DisplayName("全部 @Query 在上下文启动时解析通过（语法闸）")
    void contextBootParsesAllQueries() {
        // 到达这里 = 仓库 bean 初始化成功 = 全部 JPQL 解析通过
        assertThat(repository.countByRecipientUserId(RECIPIENT)).isEqualTo(4);
    }

    @Test
    @DisplayName("关键词检索：标题/正文命中，类型不限")
    void findByKeyword() {
        PageRequest page = PAGE;
        assertThat(repository.findByRecipientUserIdAndKeyword(RECIPIENT, "审批", page).getTotalElements()).isEqualTo(2);
        assertThat(repository.countByRecipientUserIdAndKeyword(RECIPIENT, "维护")).isEqualTo(1);
    }

    @Test
    @DisplayName("审批类口径：仅按 notification_type 枚举过滤（APPROVAL_*）")
    void findApprovalLike() {
        assertThat(repository.findApprovalLikeByRecipientUserId(RECIPIENT, PAGE).getTotalElements()).isEqualTo(2);
        assertThat(repository.countApprovalLikeByRecipientUserId(RECIPIENT)).isEqualTo(2);
    }

    @Test
    @DisplayName("审批类 + 关键词：两个谓词叠加")
    void findApprovalLikeWithKeyword() {
        assertThat(repository.findApprovalLikeByRecipientUserIdAndKeyword(RECIPIENT, "审核", PAGE).getTotalElements()).isEqualTo(1);
        assertThat(repository.countApprovalLikeByRecipientUserIdAndKeyword(RECIPIENT, "审批")).isEqualTo(2);
        assertThat(repository.countApprovalLikeByRecipientUserIdAndKeyword(RECIPIENT, "催办")).isZero();
    }

    @Test
    @DisplayName("审批类未读计数：类型 + 状态两谓词叠加")
    void countApprovalLikeUnread() {
        assertThat(repository.countApprovalLikeUnreadByRecipientUserId(RECIPIENT)).isEqualTo(1);
    }

    @Test
    @DisplayName("催办类 + 关键词：类型限定为 REMINDER")
    void findReminderWithKeyword() {
        assertThat(repository.findReminderByRecipientUserIdAndKeyword(RECIPIENT, "填报", PAGE).getTotalElements()).isEqualTo(1);
        assertThat(repository.countReminderByRecipientUserIdAndKeyword(RECIPIENT, "催办")).isEqualTo(1);
        assertThat(repository.countReminderByRecipientUserIdAndKeyword(RECIPIENT, "审批")).isZero();
    }
}
