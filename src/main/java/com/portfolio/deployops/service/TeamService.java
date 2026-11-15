package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Team;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.TeamForm;
import com.portfolio.deployops.repository.TeamRepository;
import com.portfolio.deployops.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<Team> findAll() {
        List<Team> teams = teamRepository.findByActiveTrueOrderByNameAsc();
        teams.forEach(this::attachMembers);
        return teams;
    }

    @Transactional(readOnly = true)
    public Team findById(Long id) {
        Team team = teamRepository.findByIdWithMembers(id)
            .orElseThrow(() -> new IllegalArgumentException("팀을 찾을 수 없습니다."));
        attachMembers(team);
        return team;
    }

    @Transactional
    public Team create(TeamForm form) {
        if (teamRepository.existsByName(form.getName())) {
            throw new IllegalArgumentException("이미 존재하는 팀 이름입니다.");
        }
        Team team = Team.builder()
            .name(form.getName())
            .description(form.getDescription())
            .build();
        if (form.getLeaderId() != null) {
            userRepository.findById(form.getLeaderId()).ifPresent(team::setLeader);
        }
        Team saved = teamRepository.save(team);
        reassignMembers(saved, form.getMemberIds());
        attachMembers(saved);
        return saved;
    }

    @Transactional
    public Team update(Long id, TeamForm form) {
        Team team = teamRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("팀을 찾을 수 없습니다."));
        team.setName(form.getName());
        team.setDescription(form.getDescription());
        if (form.getLeaderId() != null) {
            userRepository.findById(form.getLeaderId()).ifPresent(team::setLeader);
        } else {
            team.setLeader(null);
        }
        clearMembers(team.getId());
        reassignMembers(team, form.getMemberIds());
        Team saved = teamRepository.save(team);
        attachMembers(saved);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Team team = teamRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("팀을 찾을 수 없습니다."));
        clearMembers(id);
        team.setActive(false);
        teamRepository.save(team);
    }

    private void clearMembers(Long teamId) {
        List<User> oldMembers = userRepository.findByTeamId(teamId);
        oldMembers.forEach(u -> userRepository.updateTeam(u.getId(), null));
    }

    private void reassignMembers(Team team, List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }
        List<User> members = userRepository.findAllById(memberIds);
        members.forEach(u -> userRepository.updateTeam(u.getId(), team.getId()));
    }

    private void attachMembers(Team team) {
        team.setMembers(userRepository.findByTeamId(team.getId()));
    }
}
