package _ITHON.ReturnZone.domain.lostpost.service;

import _ITHON.ReturnZone.domain.lostpost.dto.req.LostPostRequestDto;
import _ITHON.ReturnZone.domain.lostpost.dto.res.LostPostResponseDto;
import _ITHON.ReturnZone.domain.lostpost.dto.res.SimpleLostPostResponseDto;
import _ITHON.ReturnZone.domain.lostpost.entity.LostPost;
import _ITHON.ReturnZone.domain.lostpost.entity.SortType;
import _ITHON.ReturnZone.domain.lostpost.exception.LostPostNotFoundException;
import _ITHON.ReturnZone.domain.lostpost.repository.LostPostRepository;
import _ITHON.ReturnZone.domain.member.entity.Member;
import _ITHON.ReturnZone.domain.member.repository.MemberRepository;
import _ITHON.ReturnZone.domain.lostpost.dto.res.KakaoAddressResponse;
import _ITHON.ReturnZone.domain.lostpost.service.KakaoLocalApiService;
import _ITHON.ReturnZone.global.aws.s3.AwsS3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.AccessDeniedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LostPostService {

    private final LostPostRepository lostPostRepository;
    private final MemberRepository memberRepository;
    private final KakaoLocalApiService kakaoLocalApiService;
    private final AwsS3Uploader awsS3Uploader;

    // --- 기존 조회 기능 ---

    @Transactional(readOnly = true)
    public Slice<SimpleLostPostResponseDto> getLostPostList(SortType sort, Double lat, Double lng,
                                                            Boolean instant, String category, Pageable pageable) {

        log.info("[분실물 목록 조회] page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());

        Pageable finalPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Slice<LostPost> lostPostSlice;

        if (sort == SortType.DISTANCE) {
            if (lat == null || lng == null) {
                throw new IllegalArgumentException("거리순 정렬에는 latitude/longitude 값이 필요합니다.");
            }
            Pageable distancePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
            lostPostSlice = lostPostRepository.findByFilterOrderByDistance(lat, lng, category, instant, distancePageable);
        } else {
            // 기본 최신순 정렬
            lostPostSlice = lostPostRepository.findByFilter(category, instant, finalPageable);
        }

        log.info("[분실물 목록 조회 성공]");

        return lostPostSlice.map(lostPost -> SimpleLostPostResponseDto.builder().lostPost(lostPost).build());
    }

    @Transactional(readOnly = true)
    public LostPostResponseDto getLostPost(Long lostPostId) {

        log.info("[분실물 정보 상세 조회 요청]");

        LostPost lostPost = lostPostRepository.findById(lostPostId)
                .orElseThrow(() -> {
                    log.warn("[분실물 조회 실패] 분실물 없음: lostPostId={}", lostPostId);
                    return new LostPostNotFoundException("존재하지 않는 분실물입니다. ID: " + lostPostId);
                });

        Member member = memberRepository.findById(lostPost.getMemberId())
                .orElseThrow(() -> {
                    log.warn("[회원 조회 실패] 회원 없음: memberId={}", lostPost.getMemberId());
                    return new IllegalArgumentException("작성 회원 정보가 존재하지 않습니다.");
                });

        log.info("[분실물 정보 상세 조회 성공]");

        return LostPostResponseDto.builder().lostPost(lostPost).nickname(member.getNickname()).build();
    }

    // 1. 게시글 생성 (Create)
    @Transactional
    public LostPostResponseDto createLostPost(LostPostRequestDto requestDto, List<MultipartFile> images, Long currentMemberId) {
        log.info("[분실물 게시글 생성 요청]");

        // 이미지 개수 제한 로직 추가
        if (images != null && images.size() > 5) {
            throw new IllegalArgumentException("이미지는 최대 5개까지만 업로드할 수 있습니다.");
        }

        // S3 이미지 업로드 및 URL 리스트 생성
        List<String> imageUrls = new ArrayList<>();
        if (images != null && !images.isEmpty()) {
            for (MultipartFile file : images) {
                try {
                    String imageUrl = awsS3Uploader.upload(file, "lost-posts"); // "lost-posts" 디렉토리에 업로드
                    imageUrls.add(imageUrl);
                } catch (IOException e) {
                    log.error("이미지 파일 S3 업로드 실패: {}", e.getMessage(), e);
                    throw new RuntimeException("이미지 파일 업로드에 실패했습니다.", e);
                }
            }
        }

        // 상세 위치는 requestDto에서 직접 입력받은 값을 사용합니다.
        String finalDetailedLocation = requestDto.getDetailedLocation();

        // 분실 지역(동)은 카카오 API를 통해서만 얻습니다.
        String finalLostLocationDong; // 변수 선언

        // 위도/경도는 requestDto에서 @NotNull로 필수값이 되었으므로 항상 존재함
        log.info("위도/경도로 주소 정보 변환 시도: lng={}, lat={}", requestDto.getLongitude(), requestDto.getLatitude());
        KakaoAddressResponse.Document addressDoc = kakaoLocalApiService.getAddressFromCoordinates(
                requestDto.getLongitude(), requestDto.getLatitude()
        );

        if (addressDoc == null) {
            throw new IllegalArgumentException("좌표로 분실 지역(동) 정보를 찾을 수 없습니다. (카카오 API 응답 없음 또는 주소 없음)");
        }

        // 동 정보 추출 로직 (road_address -> address -> 2depth 순)
        String extractedDong = null;
        // API 응답 JSON 구조에 맞춰 null이 아닌지, 비어있지 않은지 확인하며 추출
        if (addressDoc.getRoad_address() != null && addressDoc.getRoad_address().getRegion_3depth_name() != null && !addressDoc.getRoad_address().getRegion_3depth_name().isEmpty()) {
            extractedDong = addressDoc.getRoad_address().getRegion_3depth_name();
        } else if (addressDoc.getAddress() != null && addressDoc.getAddress().getRegion_3depth_name() != null && !addressDoc.getAddress().getRegion_3depth_name().isEmpty()) {
            extractedDong = addressDoc.getAddress().getRegion_3depth_name();
        } else if (addressDoc.getRoad_address() != null && addressDoc.getRoad_address().getRegion_2depth_name() != null && !addressDoc.getRoad_address().getRegion_2depth_name().isEmpty()) {
            extractedDong = addressDoc.getRoad_address().getRegion_2depth_name(); // 구/군으로 대체
        } else if (addressDoc.getAddress() != null && addressDoc.getAddress().getRegion_2depth_name() != null && !addressDoc.getAddress().getRegion_2depth_name().isEmpty()) {
            extractedDong = addressDoc.getAddress().getRegion_2depth_name(); // 구/군으로 대체
        }

        if (extractedDong == null || extractedDong.isEmpty()) {
            throw new IllegalArgumentException("좌표로 분실 지역(동) 정보를 찾을 수 없습니다. (카카오 API 결과에 동 정보 없음)");
        }

        finalLostLocationDong = extractedDong; // 카카오 API에서 얻은 동 정보

        log.info("주소 변환 성공: lostLocationDong={}, detailedLocation={}", finalLostLocationDong, finalDetailedLocation);

        LostPost lostPost = new LostPost(
                currentMemberId,
                requestDto.getTitle(),
                imageUrls, // S3에서 업로드된 이미지 URL
                requestDto.getDescription(),
                requestDto.getCategory(),
                requestDto.getItemName(),
                finalLostLocationDong,   // 카카오 API에서 얻은 동 정보
                finalDetailedLocation,   // 사용자가 직접 입력한 상세 위치 정보
                requestDto.getLongitude(), // requestDto에서 받은 longitude 사용
                requestDto.getLatitude(),  // requestDto에서 받은 latitude 사용
                requestDto.getLostDateTimeStart(),
                requestDto.getLostDateTimeEnd(),
                requestDto.getFeature1(),
                requestDto.getFeature2(),
                requestDto.getFeature3(),
                requestDto.getFeature4(),
                requestDto.getFeature5(),
                requestDto.getReward(),
                requestDto.isInstantSettlement(),
                requestDto.getRegistrationType()
        );
        LostPost savedPost = lostPostRepository.save(lostPost);

        Member member = memberRepository.findById(savedPost.getMemberId())
                .orElseThrow(() -> new IllegalArgumentException("작성 회원 정보가 존재하지 않습니다.")); // TODO: 실제 회원 조회 로직에 맞게 수정

        log.info("[분실물 게시글 생성 성공] lostPostId={}", savedPost.getId());
        return LostPostResponseDto.builder().lostPost(savedPost).nickname(member.getNickname()).build();
    }

    // 2. 게시글 수정 (Update)
    @Transactional
    public LostPostResponseDto updateLostPost(Long lostPostId, LostPostRequestDto requestDto, List<MultipartFile> images, Long currentMemberId) {
        log.info("[분실물 게시글 수정 요청] lostPostId={}", lostPostId);

        LostPost existingPost = lostPostRepository.findById(lostPostId)
                .orElseThrow(() -> {
                    log.warn("[분실물 수정 실패] 분실물 없음: lostPostId={}", lostPostId);
                    return new LostPostNotFoundException("수정할 게시글을 찾을 수 없습니다. ID: " + lostPostId);
                });

        // --- 본인 확인 로직  ---
        if (!existingPost.getMemberId().equals(currentMemberId)) {
            log.warn("[분실물 수정 실패] 권한 없음: lostPostId={}, 요청자 memberId={}", lostPostId, currentMemberId);
            // AccessDeniedException 대신 일반 RuntimeException 사용 또는 Custom Exception 정의
            throw new RuntimeException("해당 게시글을 수정할 권한이 없습니다.");
        }

        // 이미지 개수 제한 로직 추가 (수정 시에도 적용)
        if (images != null && images.size() > 5) {
            throw new IllegalArgumentException("이미지는 최대 5개까지만 업로드할 수 있습니다.");
        }

        // S3 이미지 업로드 및 URL 리스트 생성 (수정 시)
        List<String> updatedImageUrls = new ArrayList<>();
        if (images != null && !images.isEmpty()) {
            // 새로운 이미지가 제공되면 기존 이미지를 대체
            for (MultipartFile file : images) {
                try {
                    String imageUrl = awsS3Uploader.upload(file, "lost-posts"); // "lost-posts" 디렉토리에 업로드
                    updatedImageUrls.add(imageUrl);
                } catch (IOException e) {
                    log.error("이미지 파일 S3 업로드 실패 (수정): {}", e.getMessage(), e);
                    throw new RuntimeException("이미지 파일 업로드에 실패했습니다.", e);
                }
            }
            existingPost.setImageUrls(updatedImageUrls); // 새 이미지 URL로 갱신
        } else {
            // 새로운 이미지가 제공되지 않으면, requestDto에 담겨온 이미지 URL 목록을 사용
            // (프론트엔드에서 기존 이미지를 유지하고 싶을 경우 해당 URL을 requestDto.getImageUrls()에 포함하여 보내야 함)
            existingPost.setImageUrls(requestDto.getImageUrls());
        }

        // 상세 위치는 사용자가 requestDto에서 입력한 값을 그대로 사용
        String finalDetailedLocation = requestDto.getDetailedLocation();

        // 분실 지역(동)을 저장할 변수를 기존 값으로 초기화 (위도/경도가 제공되지 않으면 기존 값 유지)
        String finalLostLocationDong = existingPost.getLostLocationDong();

        // 수정 요청에 위도/경도가 포함되어 있다면, 다시 카카오 API를 통해 동 정보 갱신
        // (longitude, latitude는 RequestDto에서 @NotNull이므로 항상 존재)
        log.info("수정 요청에 위도/경도 포함됨. 주소 정보 재변환 시도: lng={}, lat={}", requestDto.getLongitude(), requestDto.getLatitude());
        KakaoAddressResponse.Document addressDoc = kakaoLocalApiService.getAddressFromCoordinates(
                requestDto.getLongitude(), requestDto.getLatitude()
        );

        if (addressDoc == null) {
            throw new IllegalArgumentException("수정 시 좌표로 분실 지역(동) 정보를 찾을 수 없습니다. (카카오 API 응답 없음)");
        }

        // 동 정보 추출 로직 (road_address -> address -> 2depth 순)
        String extractedDong = null;
        if (addressDoc.getRoad_address() != null && addressDoc.getRoad_address().getRegion_3depth_name() != null && !addressDoc.getRoad_address().getRegion_3depth_name().isEmpty()) {
            extractedDong = addressDoc.getRoad_address().getRegion_3depth_name();
        } else if (addressDoc.getAddress() != null && addressDoc.getAddress().getRegion_3depth_name() != null && !addressDoc.getAddress().getRegion_3depth_name().isEmpty()) {
            extractedDong = addressDoc.getAddress().getRegion_3depth_name();
        } else if (addressDoc.getRoad_address() != null && addressDoc.getRoad_address().getRegion_2depth_name() != null && !addressDoc.getRoad_address().getRegion_2depth_name().isEmpty()) {
            extractedDong = addressDoc.getRoad_address().getRegion_2depth_name(); // 구/군으로 대체
        } else if (addressDoc.getAddress() != null && addressDoc.getAddress().getRegion_2depth_name() != null && !addressDoc.getAddress().getRegion_2depth_name().isEmpty()) {
            extractedDong = addressDoc.getAddress().getRegion_2depth_name(); // 구/군으로 대체
        }

        if (extractedDong == null || extractedDong.isEmpty()) {
            throw new IllegalArgumentException("수정 시 좌표로 분실 지역(동) 정보를 정확히 찾을 수 없습니다.");
        }
        finalLostLocationDong = extractedDong; // 카카오 API에서 얻은 새로운 동 정보
        log.info("수정 시 동 정보 변환 성공: lostLocationDong={}", finalLostLocationDong);


        // 이제 기존 엔티티의 필드들을 requestDto와 최종 결정된 주소 정보로 업데이트
        existingPost.setTitle(requestDto.getTitle());
        existingPost.setDescription(requestDto.getDescription());
        existingPost.setCategory(requestDto.getCategory());
        existingPost.setItemName(requestDto.getItemName());
        existingPost.setLostLocationDong(finalLostLocationDong);   // 카카오 API에서 얻은 동 정보로 업데이트
        existingPost.setDetailedLocation(finalDetailedLocation);   // 사용자가 직접 입력한 상세 위치 정보로 업데이트
        existingPost.setLongitude(requestDto.getLongitude());
        existingPost.setLatitude(requestDto.getLatitude());
        existingPost.setLostDateTimeStart(requestDto.getLostDateTimeStart());
        existingPost.setLostDateTimeEnd(requestDto.getLostDateTimeEnd());
        existingPost.setFeature1(requestDto.getFeature1());
        existingPost.setFeature2(requestDto.getFeature2());
        existingPost.setFeature3(requestDto.getFeature3());
        existingPost.setFeature4(requestDto.getFeature4());
        existingPost.setFeature5(requestDto.getFeature5());
        existingPost.setReward(requestDto.getReward());
        existingPost.setInstantSettlement(requestDto.isInstantSettlement());
        existingPost.setRegistrationType(requestDto.getRegistrationType());

        LostPost updatedPost = lostPostRepository.save(existingPost);

        Member member = memberRepository.findById(updatedPost.getMemberId())
                .orElseThrow(() -> new IllegalArgumentException("작성 회원 정보가 존재하지 않습니다."));

        log.info("[분실물 게시글 수정 성공] lostPostId={}", updatedPost.getId());
        return LostPostResponseDto.builder().lostPost(updatedPost).nickname(member.getNickname()).build();
    }

    // 3. 게시글 삭제 (Delete)
    @Transactional
    public void deleteLostPost(Long lostPostId, Long currentMemberId) { // currentMemberId 파라미터 추가
        log.info("[분실물 게시글 삭제 요청] lostPostId={}", lostPostId);

        LostPost lostPost = lostPostRepository.findById(lostPostId)
                .orElseThrow(() -> {
                    log.warn("[분실물 삭제 실패] 분실물 없음: lostPostId={}", lostPostId);
                    return new LostPostNotFoundException("삭제할 게시글을 찾을 수 없습니다. ID: " + lostPostId);
                });

        // --- 본인 확인 로직 ---
        if (!lostPost.getMemberId().equals(currentMemberId)) {
            log.warn("[분실물 삭제 실패] 권한 없음: lostPostId={}, 요청자 memberId={}", lostPostId, currentMemberId);
            // Spring Security를 사용하지 않으므로 RuntimeException 사용
            throw new RuntimeException("해당 게시글을 삭제할 권한이 없습니다.");
        }
        // --- 본인 확인 로직 끝 ---

        lostPostRepository.delete(lostPost);
        log.info("[분실물 게시글 삭제 성공] lostPostId={}", lostPostId);
    }

    // Spring Security를 사용하지 않으므로 이 메서드는 더 이상 필요 없습니다.
    // 사용자 ID는 컨트롤러에서 받아서 서비스 메서드로 직접 전달해야 합니다.
    // private Long getCurrentMemberId() { /* ... */ }
}
