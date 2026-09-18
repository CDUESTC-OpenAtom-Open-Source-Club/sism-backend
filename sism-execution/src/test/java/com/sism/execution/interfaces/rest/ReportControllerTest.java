package com.sism.execution.interfaces.rest;

import com.sism.common.PageResult;
import com.sism.execution.application.ReportApplicationService;
import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.PlanReportIndicatorSnapshot;
import com.sism.execution.domain.report.ReportOrgType;
import com.sism.execution.interfaces.dto.CreatePlanReportRequest;
import com.sism.execution.interfaces.dto.PlanReportQueryRequest;
import com.sism.execution.interfaces.dto.RejectPlanReportRequest;
import com.sism.execution.interfaces.dto.UpdatePlanReportRequest;
import com.sism.shared.application.dto.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Execution ReportController security tests")
class ReportControllerTest {

    @Mock
    private ReportApplicationService reportApplicationService;

    @Mock
    private CurrentUser currentUser;

    @Test
    @DisplayName("createReport should use authenticated user and current org only")
    void createReportShouldUseAuthenticatedUserAndCurrentOrgOnly() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getId()).thenReturn(101L);
        when(currentUser.getOrgId()).thenReturn(10L);

        PlanReport report = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 200L, 101L);
        report.setId(1L);
        when(reportApplicationService.createReport("2026-04", 10L, ReportOrgType.FUNC_DEPT, 200L, 101L))
                .thenReturn(report);

        CreatePlanReportRequest request = new CreatePlanReportRequest();
        request.setReportMonth("2026-04");
        request.setReportOrgId(10L);
        request.setReportOrgType(ReportOrgType.FUNC_DEPT);
        request.setPlanId(200L);
        request.setCreatedBy(999L);

        var response = controller.createReport(request, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        verify(reportApplicationService).createReport("2026-04", 10L, ReportOrgType.FUNC_DEPT, 200L, 101L);
    }

    @Test
    @DisplayName("updateReport should ignore request operator user id")
    void updateReportShouldIgnoreRequestOperatorUserId() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getId()).thenReturn(102L);
        when(currentUser.getOrgId()).thenReturn(10L);

        PlanReport existing = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 201L, 102L);
        existing.setId(2L);
        when(reportApplicationService.findReportById(2L)).thenReturn(Optional.of(existing));
        when(reportApplicationService.updateReport(
                eq(2L),
                eq("title"),
                eq(11L),
                eq("content"),
                eq("summary"),
                eq(55),
                eq("issues"),
                eq("next"),
                eq(102L)
        )).thenReturn(existing);

        UpdatePlanReportRequest request = new UpdatePlanReportRequest();
        request.setTitle("title");
        request.setIndicatorId(11L);
        request.setContent("content");
        request.setSummary("summary");
        request.setProgress(55);
        request.setIssues("issues");
        request.setNextPlan("next");
        request.setOperatorUserId(999L);

        var response = controller.updateReport(2L, request, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        verify(reportApplicationService).updateReport(
                2L, "title", 11L, "content", "summary", 55, "issues", "next", 102L);
    }

    @Test
    @DisplayName("submitReport should use authenticated user id")
    void submitReportShouldUseAuthenticatedUserId() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getId()).thenReturn(103L);
        when(currentUser.getOrgId()).thenReturn(10L);

        PlanReport existing = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 202L, 103L);
        existing.setId(3L);
        when(reportApplicationService.findReportById(3L)).thenReturn(Optional.of(existing));
        when(reportApplicationService.submitReport(3L, 103L, currentUser)).thenReturn(existing);

        var response = controller.submitReport(3L, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        verify(reportApplicationService).submitReport(3L, 103L, currentUser);
    }

    @Test
    @DisplayName("rejectReport should ignore request user id")
    void rejectReportShouldIgnoreRequestUserId() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getId()).thenReturn(104L);
        when(currentUser.getOrgId()).thenReturn(10L);

        PlanReport existing = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 203L, 104L);
        existing.setId(4L);
        existing.setStatus(PlanReport.STATUS_SUBMITTED);
        when(reportApplicationService.findReportById(4L)).thenReturn(Optional.of(existing));
        when(reportApplicationService.rejectReport(4L, 104L, "bad", currentUser)).thenReturn(existing);

        RejectPlanReportRequest request = new RejectPlanReportRequest();
        request.setReason("bad");

        var response = controller.rejectReport(4L, request, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        verify(reportApplicationService).rejectReport(4L, 104L, "bad", currentUser);
    }

    @Test
    @DisplayName("getAllReports should only return reports from current org")
    void getAllReportsShouldOnlyReturnCurrentOrgReports() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getOrgId()).thenReturn(10L);

        PlanReport ownReport = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 204L, 105L);
        ownReport.setId(5L);
        Page<PlanReport> reportPage = org.mockito.Mockito.mock(Page.class);
        when(reportPage.getContent()).thenReturn(List.of(ownReport));
        when(reportPage.getTotalElements()).thenReturn(1L);
        when(reportPage.getNumber()).thenReturn(0);
        when(reportPage.getSize()).thenReturn(10);
        when(reportApplicationService.findReportsByOrgId(10L, 1, 10)).thenReturn(reportPage);

        var response = controller.getAllReports(1, 10, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        PageResult<?> pageResult = response.getBody().getData();
        assertEquals(1, pageResult.getItems().size());
        assertEquals(1, pageResult.getTotal());
        verify(reportApplicationService).findReportsByOrgId(10L, 1, 10);
    }

    @Test
    @DisplayName("getAllReports should use global pagination for admin")
    void getAllReportsShouldUseGlobalPaginationForAdmin() {
        ReportController controller = new ReportController(reportApplicationService);

        doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).when(currentUser).getAuthorities();

        PlanReport report = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 204L, 105L);
        report.setId(5L);
        Page<PlanReport> reportPage = org.mockito.Mockito.mock(Page.class);
        when(reportPage.getContent()).thenReturn(List.of(report));
        when(reportPage.getTotalElements()).thenReturn(9L);
        when(reportPage.getNumber()).thenReturn(0);
        when(reportPage.getSize()).thenReturn(10);
        when(reportApplicationService.findAllActiveReports(1, 10)).thenReturn(reportPage);

        var response = controller.getAllReports(1, 10, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        PageResult<?> pageResult = response.getBody().getData();
        assertEquals(1, pageResult.getItems().size());
        assertEquals(9, pageResult.getTotal());
        verify(reportApplicationService).findAllActiveReports(1, 10);
    }

    @Test
    @DisplayName("getReportById should reject reports from other orgs")
    void getReportByIdShouldRejectOtherOrgReports() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getOrgId()).thenReturn(10L);

        PlanReport foreignReport = PlanReport.createDraft("2026-04", 11L, ReportOrgType.FUNC_DEPT, 206L, 106L);
        foreignReport.setId(7L);
        when(reportApplicationService.findReportById(7L)).thenReturn(Optional.of(foreignReport));

        assertThrows(AccessDeniedException.class, () -> controller.getReportById(7L, currentUser));
    }

    @Test
    @DisplayName("getReportById should tolerate null indicator details")
    void getReportByIdShouldTolerateNullIndicatorDetails() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getOrgId()).thenReturn(10L);

        PlanReport report = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 300L, 106L);
        report.setId(9L);
        report.setIndicatorDetails(null);
        when(reportApplicationService.findReportById(9L)).thenReturn(Optional.of(report));

        var response = controller.getReportById(9L, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getData().getIndicatorDetails().size());
    }

    @Test
    @DisplayName("getReportById should tolerate null attachment lists")
    void getReportByIdShouldTolerateNullAttachmentLists() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getOrgId()).thenReturn(10L);

        PlanReport report = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 301L, 106L);
        report.setId(10L);
        report.setIndicatorDetails(List.of(new PlanReportIndicatorSnapshot(77L, 45, "comment", null, null, List.of())));
        when(reportApplicationService.findReportById(10L)).thenReturn(Optional.of(report));

        var response = controller.getReportById(10L, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getData().getIndicatorDetails().size());
        assertNotNull(response.getBody().getData().getIndicatorDetails().get(0).getAttachments());
        assertEquals(0, response.getBody().getData().getIndicatorDetails().get(0).getAttachments().size());
    }

    @Test
    @DisplayName("getReportsByPlanId should tolerate null indicator details")
    void getReportsByPlanIdShouldTolerateNullIndicatorDetails() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getOrgId()).thenReturn(10L);
        PlanReport report = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 4036L, 106L);
        report.setId(8L);
        report.setIndicatorDetails(null);
        when(reportApplicationService.findReportsByPlanIdForOrg(4036L, 10L)).thenReturn(List.of(report));

        var response = controller.getReportsByPlanId(4036L, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getData().size());
        assertNotNull(response.getBody().getData().get(0).getIndicatorDetails());
    }

    @Test
    @DisplayName("getReportsByStatusPaginated should preserve page total elements")
    void getReportsByStatusPaginatedShouldPreserveTotalElements() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getOrgId()).thenReturn(10L);
        Page<PlanReport> reportPage = org.mockito.Mockito.mock(Page.class);
        PlanReport report = PlanReport.createDraft("2026-04", 10L, ReportOrgType.FUNC_DEPT, 200L, 105L);
        when(reportPage.getContent()).thenReturn(List.of(report));
        when(reportPage.getTotalElements()).thenReturn(7L);
        when(reportPage.getNumber()).thenReturn(0);
        when(reportPage.getSize()).thenReturn(10);
        when(reportApplicationService.findReportsByStatusForOrg("SUBMITTED", 10L, 1, 10))
                .thenReturn(reportPage);

        var response = controller.getReportsByStatusPaginated("SUBMITTED", 1, 10, currentUser);

        PageResult<?> pageResult = response.getBody().getData();
        assertEquals(7, pageResult.getTotal());
        assertEquals(1, pageResult.getItems().size());
    }

    @Test
    @DisplayName("searchReports should constrain non-admin to current org in query layer")
    void searchReportsShouldConstrainNonAdminToCurrentOrgInQueryLayer() {
        ReportController controller = new ReportController(reportApplicationService);

        when(currentUser.getOrgId()).thenReturn(10L);
        Page<PlanReport> reportPage = org.mockito.Mockito.mock(Page.class);
        PlanReport report = PlanReport.createDraft("202604", 10L, ReportOrgType.FUNC_DEPT, 200L, 105L);
        when(reportPage.getContent()).thenReturn(List.of(report));
        when(reportPage.getTotalElements()).thenReturn(1L);
        when(reportPage.getNumber()).thenReturn(0);
        when(reportPage.getSize()).thenReturn(10);

        PlanReportQueryRequest queryRequest = new PlanReportQueryRequest();
        queryRequest.setStatus("SUBMITTED");
        queryRequest.setPage(1);
        queryRequest.setSize(10);

        when(reportApplicationService.findReportsByConditionsForOrg(queryRequest, 10L))
                .thenReturn(reportPage);

        var response = controller.searchReports(queryRequest, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        verify(reportApplicationService).findReportsByConditionsForOrg(queryRequest, 10L);
    }
}
