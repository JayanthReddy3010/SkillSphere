package com.skillsphere.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;

class EmailServiceConditionalConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JavaMailSender.class, () -> Mockito.mock(JavaMailSender.class))
            .withUserConfiguration(ConsoleEmailService.class, SmtpEmailService.class);

    @Test
    @DisplayName("When spring.mail.host is not configured, only ConsoleEmailService is active")
    void whenMailHostNotConfigured_thenOnlyConsoleEmailServiceActive() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EmailService.class);
            assertThat(context).hasBean("consoleEmailService");
            assertThat(context).doesNotHaveBean("smtpEmailService");
            assertThat(context.getBean(EmailService.class)).isInstanceOf(ConsoleEmailService.class);
        });
    }

    @Test
    @DisplayName("When spring.mail.host is empty string, only ConsoleEmailService is active")
    void whenMailHostEmptyString_thenOnlyConsoleEmailServiceActive() {
        contextRunner.withPropertyValues("spring.mail.host=").run(context -> {
            assertThat(context).hasSingleBean(EmailService.class);
            assertThat(context).hasBean("consoleEmailService");
            assertThat(context).doesNotHaveBean("smtpEmailService");
            assertThat(context.getBean(EmailService.class)).isInstanceOf(ConsoleEmailService.class);
        });
    }

    @Test
    @DisplayName("When spring.mail.host is whitespace, only ConsoleEmailService is active")
    void whenMailHostWhitespace_thenOnlyConsoleEmailServiceActive() {
        contextRunner.withPropertyValues("spring.mail.host=   ").run(context -> {
            assertThat(context).hasSingleBean(EmailService.class);
            assertThat(context).hasBean("consoleEmailService");
            assertThat(context).doesNotHaveBean("smtpEmailService");
            assertThat(context.getBean(EmailService.class)).isInstanceOf(ConsoleEmailService.class);
        });
    }

    @Test
    @DisplayName("When spring.mail.host is configured, only SmtpEmailService is active")
    void whenMailHostConfigured_thenOnlySmtpEmailServiceActive() {
        contextRunner.withPropertyValues("spring.mail.host=smtp.gmail.com").run(context -> {
            assertThat(context).hasSingleBean(EmailService.class);
            assertThat(context).hasBean("smtpEmailService");
            assertThat(context).doesNotHaveBean("consoleEmailService");
            assertThat(context.getBean(EmailService.class)).isInstanceOf(SmtpEmailService.class);
        });
    }

    @Test
    @DisplayName("PasswordResetService successfully autowires with ConsoleEmailService when SMTP is absent")
    void passwordResetServiceAutowiresWithConsoleEmailService() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConsoleEmailService.class, SmtpEmailService.class)
                .withBean(JavaMailSender.class, () -> Mockito.mock(JavaMailSender.class))
                .withBean(com.skillsphere.repository.UserRepository.class, () -> Mockito.mock(com.skillsphere.repository.UserRepository.class))
                .withBean(com.skillsphere.repository.PasswordResetOtpRepository.class, () -> Mockito.mock(com.skillsphere.repository.PasswordResetOtpRepository.class))
                .withBean(com.skillsphere.repository.PasswordResetTokenRepository.class, () -> Mockito.mock(com.skillsphere.repository.PasswordResetTokenRepository.class))
                .withBean(org.springframework.security.crypto.password.PasswordEncoder.class, () -> Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class))
                .withBean(OtpRateLimiterService.class, () -> Mockito.mock(OtpRateLimiterService.class))
                .withBean(PasswordResetService.class)
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "app.otp.allow-dev-fallback=true",
                        "app.otp.hash-secret=test-secret-at-least-32-chars-long"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PasswordResetService.class);
                    assertThat(context).hasSingleBean(EmailService.class);
                    assertThat(context.getBean(EmailService.class)).isInstanceOf(ConsoleEmailService.class);
                });
    }

    @Test
    @DisplayName("PasswordResetService successfully autowires with SmtpEmailService when SMTP is configured")
    void passwordResetServiceAutowiresWithSmtpEmailService() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConsoleEmailService.class, SmtpEmailService.class)
                .withBean(JavaMailSender.class, () -> Mockito.mock(JavaMailSender.class))
                .withBean(com.skillsphere.repository.UserRepository.class, () -> Mockito.mock(com.skillsphere.repository.UserRepository.class))
                .withBean(com.skillsphere.repository.PasswordResetOtpRepository.class, () -> Mockito.mock(com.skillsphere.repository.PasswordResetOtpRepository.class))
                .withBean(com.skillsphere.repository.PasswordResetTokenRepository.class, () -> Mockito.mock(com.skillsphere.repository.PasswordResetTokenRepository.class))
                .withBean(org.springframework.security.crypto.password.PasswordEncoder.class, () -> Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class))
                .withBean(OtpRateLimiterService.class, () -> Mockito.mock(OtpRateLimiterService.class))
                .withBean(PasswordResetService.class)
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "app.otp.allow-dev-fallback=true",
                        "app.otp.hash-secret=test-secret-at-least-32-chars-long",
                        "spring.mail.host=smtp.gmail.com"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PasswordResetService.class);
                    assertThat(context).hasSingleBean(EmailService.class);
                    assertThat(context.getBean(EmailService.class)).isInstanceOf(SmtpEmailService.class);
                });
    }
}
