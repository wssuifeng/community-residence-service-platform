package com.community.residence.common.notice.service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class NoticeBodyPolicyTest {
 String doc(String content){return "{\"type\":\"doc\",\"content\":["+content+"]}";}
 @Test void textBoldAlignmentAndListsArePreserved(){var b=NoticeBodyPolicy.parse(doc("{\"type\":\"paragraph\",\"attrs\":{\"textAlign\":\"center\"},\"content\":[{\"type\":\"text\",\"text\":\"社区通知\",\"marks\":[{\"type\":\"bold\"}]}]}"));assertThat(b.text()).isEqualTo("社区通知");assertThat(b.json()).contains("center","bold");}
 @Test void imageStoresOnlyPlatformPathAndSafeLayout(){var b=NoticeBodyPolicy.parse(doc("{\"type\":\"image\",\"attrs\":{\"src\":\"/uploads/2026/photo.png\",\"widthPercent\":50,\"textAlign\":\"center\",\"alt\":\"活动场地\"}}"));assertThat(b.images()).containsExactly("/uploads/2026/photo.png");assertThat(b.text()).isEqualTo("[图片公告]");}
 @Test void arbitraryHtmlOrScriptNodesAreRejected(){for(String type:new String[]{"script","iframe","html","video"})assertThatThrownBy(()->NoticeBodyPolicy.parse(doc("{\"type\":\""+type+"\"}"))).hasMessageContaining("正文格式");}
 @Test void eventStyleAndLinkAttributesAreRejected(){assertThatThrownBy(()->NoticeBodyPolicy.parse(doc("{\"type\":\"paragraph\",\"attrs\":{\"onclick\":\"alert(1)\"}}"))).hasMessageContaining("正文格式");assertThatThrownBy(()->NoticeBodyPolicy.parse(doc("{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"链接\",\"marks\":[{\"type\":\"link\"}]}]}"))).hasMessageContaining("正文格式");}
 @Test void externalBase64AndTraversalImagesAreRejected(){for(String src:new String[]{"https://other.example/x.png","data:image/png;base64,xxx","/uploads/../secret.png","//other/x.png"})assertThatThrownBy(()->NoticeBodyPolicy.parse(doc("{\"type\":\"image\",\"attrs\":{\"src\":\""+src+"\",\"widthPercent\":100}}"))).hasMessageContaining("正文格式");}
 @Test void imagesCannotContainHiddenChildNodes(){assertThatThrownBy(()->NoticeBodyPolicy.parse(doc("{\"type\":\"image\",\"attrs\":{\"src\":\"/uploads/a.png\",\"widthPercent\":100},\"content\":[{\"type\":\"text\",\"text\":\"hidden\"}]}"))).hasMessageContaining("正文格式");}
 @Test void invalidRootAndEmptyDocumentAreRejected(){assertThatThrownBy(()->NoticeBodyPolicy.parse("<p>raw html</p>")).hasMessageContaining("正文格式");assertThatThrownBy(()->NoticeBodyPolicy.parse(doc("{\"type\":\"paragraph\"}"))).hasMessageContaining("正文格式");}
 @Test void tooManyImagesAreRejected(){String image="{\"type\":\"image\",\"attrs\":{\"src\":\"/uploads/a.png\",\"widthPercent\":100}}";assertThatThrownBy(()->NoticeBodyPolicy.parse(doc(String.join(",",java.util.Collections.nCopies(7,image))))).hasMessageContaining("正文格式");}
}
