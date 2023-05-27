package com.samnamja.deboost.api.service.riot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samnamja.deboost.api.dto.openfeign.response.*;
import com.samnamja.deboost.api.dto.riot.response.SummonerSearchResponseDto;
import com.samnamja.deboost.api.entity.riot.AnalysisData.AnalysisData;
import com.samnamja.deboost.api.entity.riot.AnalysisData.AnalysisDataRepository;
import com.samnamja.deboost.api.entity.riot.UserHistory.UserHistory;
import com.samnamja.deboost.api.entity.riot.UserHistory.UserHistoryRepository;
import com.samnamja.deboost.api.service.openfeign.RiotOpenFeignService;
import com.samnamja.deboost.exception.custom.CustomException;
import com.samnamja.deboost.utils.aws.AmazonS3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiotDataService {
    @Value("${riot.api-key}")
    private String riotApiKey;

    private final RiotOpenFeignService riotOpenFeignService;
    private final ManuFactureDataService manuFactureDataService;
    private final AnalysisDataRepository analysisDataRepository;
    private final UserHistoryRepository userHistoryRepository;
    private final AmazonS3Uploader amazonS3Uploader;

    @Transactional
    public SummonerSearchResponseDto analysisSummoner(String summonerName){
        ObjectMapper objectMapper = new ObjectMapper();
        List<GameSpecificDetailInfoResponseDto> response = new ArrayList<>();
        // RIOT API 호출
        SummonerInfoResponseDto summonerInfo = riotOpenFeignService.getSummonerEncryptedId(summonerName, riotApiKey);
        List<SummonerDetailInfoResponseDto> summonerDetailInfo = riotOpenFeignService.getSummonerDetailInfo(summonerInfo.getId(), riotApiKey);
        List<GameIdResponseDto> gameIds = riotOpenFeignService.getGameIds(summonerInfo.getPuuid(), riotApiKey);

        UserHistory userHistory = userHistoryRepository.findByHistoryGamerName(summonerName)
                .map(existingUserHistory -> {
                    existingUserHistory.updateIsSearched();
                    return existingUserHistory;
                })
                .orElseGet(() -> {
                    UserHistory newUserHistory = UserHistory.builder()
                            .historyGamerName(summonerName)
                            .isSearched(false)
                            .build();
                    return userHistoryRepository.save(newUserHistory);
                });

        // DB에 저장된 데이터와 비교 : API 로 불러온 gameId 중에 DB에 저장된 gameId에 해당하는 것들을 호출
        List<AnalysisData> existsAnalysisData = analysisDataRepository.findAnalysisDataBySummonerNameAndGameIds(summonerName, gameIds);
        // 이미 json 파일이 존재하는 경우 -> s3에서 json 불러와서 GameSpecificDetailInfoResponseDto 으로 load
        existsAnalysisData.stream().map(AnalysisData::getPrimaryDataUrl).collect(Collectors.toList()).stream()
                .forEach(url -> {
                    GameAllDetailInfoResponseDto gameDetailInfos = amazonS3Uploader.loadJsonFileAndConvertToDto(url);
                    response.add(GameSpecificDetailInfoResponseDto.from(gameDetailInfos, summonerName));
                });

        // 새로 추가된 게임이 있는 경우 -> API 호출해서 GameSpecificDetailInfoResponseDto 으로 load
        List<GameAllDetailInfoResponseDto> newSearchGamesDetailInfos = gameIds.stream().map(GameIdResponseDto::getGameId).collect(Collectors.toSet()).stream()
                .filter(gameId -> !existsAnalysisData.stream().map(AnalysisData::getGameId).collect(Collectors.toSet()).contains(gameId))
                .collect(Collectors.toList()).stream()
                .map(gameId -> riotOpenFeignService.getGameDetailInfos(gameId, riotApiKey)).collect(Collectors.toList());

        // S3 저장
        newSearchGamesDetailInfos.stream().forEach(gameDetailInfos -> {
            String jsonString = null;
            try {
                jsonString = objectMapper.writeValueAsString(gameDetailInfos);
            } catch (JsonProcessingException e) {
                throw CustomException.builder().message("dto to json convert failed").build();
            }
            String jsonFileS3Key = amazonS3Uploader.saveJsonStringAndGetKey(jsonString, summonerName);
            AnalysisData analysisData = AnalysisData.builder()
                    .gameId(gameDetailInfos.getMetadata().getMatchId())
                    .userHistory(userHistory)
                    .primaryDataUrl(jsonFileS3Key)
                    .build();
            analysisDataRepository.save(analysisData);
        });

        List<GameSpecificDetailInfoResponseDto> newGameSpecificDetailInfo = newSearchGamesDetailInfos.stream()
                .map(gameDetailInfos -> GameSpecificDetailInfoResponseDto.from(gameDetailInfos, summonerName))
                .collect(Collectors.toList());

        response.addAll(newGameSpecificDetailInfo);
        response.sort(Comparator.comparing(GameSpecificDetailInfoResponseDto::getGameId).reversed());

        return SummonerSearchResponseDto.builder()
                .summonerInfo(
                        SummonerSearchResponseDto.SummonerInfo.builder()
                                .summonerName(summonerInfo.getName())
                                .summonerLevel(summonerInfo.getSummonerLevel())
                                .summonerIconId(summonerInfo.getProfileIconId())
                                .tier(summonerDetailInfo.stream()
                                        .filter(detailInfo -> detailInfo.getQueueType().equals("RANKED_SOLO_5x5"))
                                        .findFirst().orElse(SummonerDetailInfoResponseDto.builder().tier("UNRANKED").build()).getTier())
                                .build()
                            )
                .gameInfos(response)
                .build();
    }

}