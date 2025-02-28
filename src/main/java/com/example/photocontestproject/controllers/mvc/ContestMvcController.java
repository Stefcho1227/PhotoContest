package com.example.photocontestproject.controllers.mvc;

import com.example.photocontestproject.dtos.ContestDto;
import com.example.photocontestproject.dtos.EntryDto;
import com.example.photocontestproject.enums.ContestPhase;
import com.example.photocontestproject.enums.ContestType;
import com.example.photocontestproject.enums.Role;
import com.example.photocontestproject.exceptions.AuthorizationException;
import com.example.photocontestproject.exceptions.EntityNotFoundException;
import com.example.photocontestproject.helpers.AuthenticationHelper;
import com.example.photocontestproject.mappers.ContestMapper;
import com.example.photocontestproject.mappers.EntryMapper;
import com.example.photocontestproject.models.Contest;
import com.example.photocontestproject.models.Entry;
import com.example.photocontestproject.models.User;
import com.example.photocontestproject.services.contracts.ContestService;
import com.example.photocontestproject.services.contracts.EntryService;
import com.example.photocontestproject.services.contracts.ImageService;
import com.example.photocontestproject.services.contracts.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/contests")
public class ContestMvcController {
    private final ContestService contestService;
    private final EntryService entryService;
    private final EntryMapper entryMapper;
    private final AuthenticationHelper authenticationHelper;
    private final ContestMapper contestMapper;
    private final UserService userService;
    private final ImageService imageService;

    public ContestMvcController(ContestService contestService,
                                EntryService entryService,
                                EntryMapper entryMapper,
                                AuthenticationHelper authenticationHelper,
                                ContestMapper contestMapper,
                                UserService userService,
                                ImageService imageService) {
        this.contestService = contestService;
        this.entryService = entryService;
        this.entryMapper = entryMapper;
        this.authenticationHelper = authenticationHelper;
        this.contestMapper = contestMapper;
        this.userService = userService;
        this.imageService = imageService;
    }

    @GetMapping("/{contestId}")
    public String showSingleContest(@PathVariable Integer contestId,
                                    Model model,
                                    HttpSession session) {
        User user;
        try {
            user = authenticationHelper.tryGetCurrentUser(session);
        } catch (AuthorizationException e) {
            session.setAttribute("redirectUrl", "/contests/" + contestId);
            return "redirect:/login";
        }
        try {
            Contest contest = contestService.getContestById(contestId);
            List<Entry> entries = contest.getEntries();
            List<Entry> sortedEntries = entries.stream()
                    .sorted(Comparator.comparing(Entry::getEntryTotalScore).reversed())
                    .limit(8)
                    .collect(Collectors.toList());
            Map<Integer, String> ranks = contestService.getRanks(sortedEntries);
            boolean alreadyEntered = entries.stream().anyMatch(entry -> entry.getParticipant().getId().equals(user.getId()));
            boolean isJuror = contest.getJurors().stream().anyMatch(juror -> juror.getId().equals(user.getId()));
            model.addAttribute("contest", contest);
            model.addAttribute("entry", new EntryDto());
            model.addAttribute("isOrganizer", user.getRole().equals(Role.Organizer));
            model.addAttribute("isPhaseI", contest.getContestPhase().equals(ContestPhase.PhaseI));
            model.addAttribute("isPhaseII", contest.getContestPhase().equals(ContestPhase.PhaseII));
            model.addAttribute("isFinished", contest.getContestPhase().equals(ContestPhase.Finished));
            model.addAttribute("isJuror", isJuror);
            model.addAttribute("isInvited", contest.getParticipants().stream().anyMatch(participant -> participant.getId().equals(user.getId())));
            model.addAttribute("isInvitational", contest.getContestType().equals(ContestType.Invitational));
            model.addAttribute("entries", entries);
            model.addAttribute("sortedEntries", sortedEntries);
            model.addAttribute("ranks", ranks);
            model.addAttribute("alreadyEntered", alreadyEntered);
            return "ContestView";
        } catch (EntityNotFoundException e) {
            model.addAttribute("statusCode", HttpStatus.NOT_FOUND.getReasonPhrase());
            model.addAttribute("notFound", e.getMessage());
            return "ErrorView";
        }
    }

    @GetMapping("/create")
    public String getCreateContestView(Model model, HttpSession session) {
        try {
            authenticationHelper.tryGetCurrentUser(session);
        } catch (AuthorizationException e) {
            session.setAttribute("redirectUrl", "/contests/create");
            return "redirect:/login";
        }
        model.addAttribute("junkies", userService.getUsersByRole(Role.Junkie));
        model.addAttribute("masters", userService.getMasters());
        model.addAttribute("contest", new ContestDto());
        return "CreateContestView";
    }

    @PostMapping("/create")
    public String handleContestCreation(@RequestParam("coverPhoto") MultipartFile photoFile,
                                        @ModelAttribute("contest") ContestDto contestDto,
                                        @RequestParam(value = "jurorIds", required = false) List<Integer> jurorIds,
                                        @RequestParam(value = "participantIds", required = false) List<Integer> participantIds,
                                        @RequestParam(value = "invitational", defaultValue = "false") boolean isInvitational,
                                        @SessionAttribute("currentUser") User user,
                                        Model model) {
        try {
            byte[] resizedImage = imageService.resizeImage(photoFile);
            String base64Image = Base64.getEncoder().encodeToString(resizedImage);
            contestDto.setCoverPhotoUrl(base64Image);
            Contest contest = contestMapper.fromDto(contestDto);
            contest.setOrganizer(user);
            contestService.createContest(contest, user);
            if (jurorIds == null) {
                jurorIds = List.of();
            }
            if (participantIds == null) {
                participantIds = List.of();
            }
            for (Integer jurorId : jurorIds) {
                contestService.addJuror(contest.getId(), jurorId, user);
            }
            for (Integer participantId : participantIds) {
                contestService.addParticipant(contest.getId(), participantId, user);
            }
            return "redirect:/";
        } catch (IOException e) {
            return "redirect:/contests/create";
        } catch (AuthorizationException e) {
            model.addAttribute("statusCode", HttpStatus.UNAUTHORIZED.getReasonPhrase());
            model.addAttribute("notOrganizer", e.getMessage());
            return "ErrorView";
        }
    }

    @PostMapping("/{contestId}/entries")
    public String createEntry(@PathVariable Integer contestId,
                              @RequestParam("photo") MultipartFile photoFile,
                              @ModelAttribute("entry") EntryDto entryDto,
                              @SessionAttribute("currentUser") User user,
                              Model model) {
        try {
            byte[] resizedImage = imageService.resizeImage(photoFile);
            String base64Image = Base64.getEncoder().encodeToString(resizedImage);
            entryDto.setPhotoUrl(base64Image);
            Entry entry = entryMapper.fromDto(entryDto, user);
            entry.setContest(contestService.getContestById(contestId));
            entryService.createEntry(entry, user);
            return "redirect:/";
        } catch (AuthorizationException e) {
            model.addAttribute("statusCode", HttpStatus.UNAUTHORIZED.getReasonPhrase());
            model.addAttribute("isOrganizer", e.getMessage());
            return "redirect:/";
        } catch (IOException e) {
            return "redirect:/contests/" + contestId;
        }
    }

}
