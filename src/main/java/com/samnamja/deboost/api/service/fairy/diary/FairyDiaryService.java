package com.samnamja.deboost.api.service.fairy.diary;

import com.samnamja.deboost.api.dto.fairy.request.DiaryMakeRequestDto;
import com.samnamja.deboost.api.dto.fairy.request.FairyRequestDto;
import com.samnamja.deboost.api.dto.fairy.request.MessageRequestDto;
import com.samnamja.deboost.api.dto.fairy.response.ChatCompletionDto;
import com.samnamja.deboost.api.dto.fairy.response.FairyTaleCoverResponseDto;
import com.samnamja.deboost.api.dto.fairy.response.FairyTaleDetailResponseDto;
import com.samnamja.deboost.api.entity.fairy.diary.Diary;
import com.samnamja.deboost.api.entity.fairy.diary.DiaryRepository;
import com.samnamja.deboost.api.entity.user.detail.UserAccount;
import com.samnamja.deboost.api.service.openfeign.FairyOpenFeignClient;
import com.samnamja.deboost.exception.custom.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FairyDiaryService {
    private final FairyOpenFeignClient fairyOpenFeignClient;

    @Value("${chatGPT.key}")
    private String openaiKey;

    @Value("${chatGPT.model}")
    private String model;

    private final DiaryRepository diaryRepository;

    public ChatCompletionDto getFairyContent(DiaryMakeRequestDto diaryMakeRequestDto) {
        List<MessageRequestDto> messageList = new ArrayList<>();
        messageList.add(MessageRequestDto.builder().role("user").content("다음 3가지 질문에 대해서 답변을 토대로 동화를 작성해줘").build());
        diaryMakeRequestDto.getContentList().stream()
                        .forEach(content -> {
                            messageList.add(MessageRequestDto.builder().role("assistant").content(content.getQuestion()).build());
                            messageList.add(MessageRequestDto.builder().role("user").content(content.getAnswer()).build());
                        });
        messageList.add(MessageRequestDto.builder().role("assistant").content("저는 위 답변들을 듣고 행복한 동화로 아이들이 좋아할 만한 동화를 창작하는 동화작가입니다. 그리고 주인공은 가상인물로 설정하겠습니다. 다음 내용을 기반으로 기승전결이 있는 4문단으로 이루어져있고 각 문단이 6줄로 이루어진 동화를 작성해드리겠습니다.").build());
        FairyRequestDto fairyRequestDto = FairyRequestDto.builder().model(model)
                .messages(messageList)
                .build();
        return fairyOpenFeignClient.getFairyContent("Bearer " + openaiKey, fairyRequestDto);
    }

    public List<FairyTaleCoverResponseDto> getMyFairyTales(UserAccount userAccount, Long cursor, Pageable pageable) {
        List<Diary> diaryList = diaryRepository.findAllByDeviceIdWithPagingOrderByCreatedAt(userAccount.getUserId(), cursor, pageable);
        return diaryList.stream().map(FairyTaleCoverResponseDto::of).collect(Collectors.toList());
    }

    public FairyTaleDetailResponseDto getFairyTaleDetailInfo(UserAccount userAccount, Long diaryId) {
        Diary diary = diaryRepository.findById(diaryId).orElseThrow(() -> CustomException.builder().httpStatus(HttpStatus.NOT_FOUND).message("해당 동화가 존재하지 않습니다.").build());
        return FairyTaleDetailResponseDto.of(diary);
    }
}