package vn.edu.bookstore.legacy.adapter.soap;

import jakarta.xml.soap.SOAPFault;
import jakarta.xml.ws.WebServiceException;
import jakarta.xml.ws.soap.SOAPFaultException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.springframework.stereotype.Component;
import vn.edu.bookstore.legacy.adapter.soap.generated.Book;
import vn.edu.bookstore.legacy.adapter.soap.generated.BookCatalogFault;
import vn.edu.bookstore.legacy.adapter.soap.generated.BookCatalogPortType;
import vn.edu.bookstore.legacy.adapter.soap.generated.ErrorDetail;
import vn.edu.bookstore.legacy.adapter.soap.generated.GetBookRequest;
import vn.edu.bookstore.legacy.adapter.soap.generated.GetBookResponse;
import vn.edu.bookstore.legacy.adapter.soap.generated.SearchBooksRequest;
import vn.edu.bookstore.legacy.adapter.soap.generated.SearchBooksResponse;

import static vn.edu.bookstore.legacy.adapter.soap.LegacyCatalogException.Kind;

@Component
public class LegacyCatalogClient {

    private final BookCatalogPortType port;

    public LegacyCatalogClient(BookCatalogPortType port) {
        this.port = port;
    }

    public LegacyBook getBook(String isbn) {
        GetBookRequest request = new GetBookRequest();
        request.setIsbn(isbn);

        try {
            GetBookResponse response = port.getBook(request);
            return toModel(response.getBook());
        } catch (BookCatalogFault fault) {
            throw translate(fault);
        } catch (SOAPFaultException e) {
            throw translate(e);
        } catch (WebServiceException e) {
            throw unavailable(e);
        }
    }

    public List<LegacyBook> search(String keyword, int maxResults) {
        SearchBooksRequest request = new SearchBooksRequest();
        request.setKeyword(keyword);
        request.setMaxResults(maxResults);

        try {
            SearchBooksResponse response = port.searchBooks(request);
            return response.getBook().stream()
                    .map(LegacyCatalogClient::toModel)
                    .toList();
        } catch (BookCatalogFault fault) {
            throw translate(fault);
        } catch (SOAPFaultException e) {
            throw translate(e);
        } catch (WebServiceException e) {
            throw unavailable(e);
        }
    }

    /**
     * TH1-7: Chuyển lỗi SOAP có ErrorDetail thành lỗi nội bộ.
     */
    static LegacyCatalogException translate(BookCatalogFault fault) {
        ErrorDetail detail = fault.getFaultInfo();

        String code = detail == null ? "UNKNOWN" : detail.getCode();
        String message = detail == null
                ? fault.getMessage()
                : detail.getMessage();

        Kind kind = switch (code) {
            case "BOOK_NOT_FOUND" -> Kind.NOT_FOUND;
            case "INVALID_ISBN", "INVALID_REQUEST" -> Kind.INVALID_REQUEST;
            default -> Kind.UPSTREAM_ERROR;
        };

        return new LegacyCatalogException(kind, code, message, fault);
    }

    /**
     * TH1-7: Chuyển SOAP fault không có ErrorDetail.
     */
    static LegacyCatalogException translate(SOAPFaultException e) {
        SOAPFault fault = e.getFault();

        String faultCode = fault == null
                || fault.getFaultCodeAsQName() == null
                ? "UNKNOWN"
                : fault.getFaultCodeAsQName().getLocalPart();

        String message = fault == null
                ? e.getMessage()
                : fault.getFaultString();

        Kind kind = "Client".equals(faultCode)
                ? Kind.INVALID_REQUEST
                : Kind.UPSTREAM_ERROR;

        return new LegacyCatalogException(
                kind,
                "soap:" + faultCode,
                message,
                e
        );
    }

    /**
     * TH1-7: Phân biệt lỗi timeout và lỗi không kết nối được.
     */
    static LegacyCatalogException unavailable(WebServiceException e) {
        boolean timeout =
                hasCause(e, SocketTimeoutException.class)
                || hasCause(e, HttpTimeoutException.class);

        Kind kind = timeout
                ? Kind.UPSTREAM_TIMEOUT
                : Kind.UPSTREAM_UNAVAILABLE;

        return new LegacyCatalogException(
                kind,
                null,
                "Không gọi được BookCatalogService: " + e.getMessage(),
                e
        );
    }

    private static boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> type) {

        for (Throwable current = throwable;
             current != null;
             current = current.getCause()) {

            if (type.isInstance(current)) {
                return true;
            }
        }

        return false;
    }

    static LegacyBook toModel(Book book) {
        Integer authorId = book.getAuthorId() == null
                ? null
                : book.getAuthorId().intValue();

        Integer year = book.getPublishedYear() == null
                ? null
                : book.getPublishedYear().getYear();

        return new LegacyBook(
                book.getIsbn(),
                book.getTitle(),
                book.getAuthor(),
                authorId,
                year,
                book.getPrice().getAmount(),
                book.getPrice().getCurrency().value()
        );
    }
}
