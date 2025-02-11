package com.project.store.controllers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.store.constants.Constant;
import com.project.store.domain.Cart;
import com.project.store.domain.CartDetail;
import com.project.store.domain.Order;
import com.project.store.domain.OrderDetail;
import com.project.store.domain.Order_;
import com.project.store.domain.User;
import com.project.store.services.CartService;
import com.project.store.services.OrderService;
import com.project.store.services.ProductService;
import com.project.store.services.UserService;
import com.project.store.services.UtilsService;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class OrderController {

    private final CartService cartService;
    private final UtilsService utilsService;
    private final OrderService orderService;

    public OrderController(
            CartService cartService,
            ProductService productService,
            UserService userService,
            OrderService orderService,
            UtilsService utilsService) {
        this.cartService = cartService;
        this.orderService = orderService;
        this.utilsService = utilsService;
    }

    @PostMapping("/proceed-checkout")
    public String proceedCheckout(Model model, @ModelAttribute("cart") Cart cart, HttpServletRequest request) {
        if (cart == null || cart.getCartDetails().isEmpty()) {
            return "redirect:/cart";
        }
        List<CartDetail> cartDetails = cart.getCartDetails();
        List<CartDetail> realCartDetails = new ArrayList<>();
        User user = this.utilsService.getSessionUser();
        double price_total = 0;
        for (CartDetail cartDetail : cartDetails) {
            CartDetail realCartDetail = this.cartService.handleGetCartDetailById(cartDetail.getId());
            //update cart detail record in database 
            realCartDetail.setQuantity(cartDetail.getQuantity());
            realCartDetail.setPrice_total(cartDetail.getQuantity() * realCartDetail.getProduct().getPrice());
            realCartDetails.add(realCartDetail);
            price_total += realCartDetail.getPrice_total();
        }

        Cart userCart = this.cartService.handleFindCartByUser(user);
        //update price total of cart in database
        userCart.setPrice_total(price_total);

        model.addAttribute("realCartDetails", realCartDetails);
        model.addAttribute("user", user);
        return "client/business/billing";
    }

    @PostMapping("/place-order")
    public String placeOrder(@RequestParam("orderNote") String orderNote) {
        User user = utilsService.getSessionUser();
        Cart cart = user.getCart();
        //create order
        Order order = new Order();
        order.setUser(user);
        order.setOrderNote(orderNote);
        order.setTotalPrice(cart.getPrice_total());
        order.setProductTotal(cart.getProduct_total());
        Order insertedOrder = this.orderService.handleSaveOrder(order);
        //create a record
        for (CartDetail cartDetail : cart.getCartDetails()) {
            OrderDetail orderDetail = new OrderDetail();
            orderDetail.setProduct(cartDetail.getProduct());
            orderDetail.setQuantity(cartDetail.getQuantity());
            orderDetail.setPrice(cartDetail.getPrice_total());
            orderDetail.setOrder(insertedOrder);
            this.orderService.handleSaveOrderDetail(orderDetail);
            //delete cart delete record
            this.cartService.handleDeleteCartDetail(cartDetail);
        }
        //delete cart of user
        this.cartService.handleDeleteCart(cart);
        utilsService.setCartSession(0);
        return "redirect:/";
    }

    @GetMapping("/order-history")
    public String getOrderHistory(Model model) {
        User user = this.utilsService.getSessionUser();
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Order_.UPDATED_AT).descending());

        Page<Order> orderPage = this.orderService.handleGetOrdersByUser(user, pageable);
        List<Order> orders = orderPage.getContent();
        model.addAttribute("orders", orders);
        return "client/business/order-history";
    }

    @GetMapping("/orders/delete/{id}")
    public String deleteOrder(@PathVariable("id") long orderId) {
        this.orderService.handleDeleteOrderById(orderId);
        return "redirect:/order-history";
    }

    @GetMapping("/orders/detail/{id}")
    public String detailOrder(Model model, @PathVariable("id") long orderId) {
        User user = this.utilsService.getSessionUser();
        Order order = this.orderService.handleFindOrderById(orderId);
        List<OrderDetail> orderDetails = order.getOrderDetails();
        List<CartDetail> realCartDetails = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetails) {
            CartDetail cartDetail = new CartDetail();
            cartDetail.setProduct(orderDetail.getProduct());
            cartDetail.setPrice_total(orderDetail.getPrice());
            cartDetail.setQuantity(orderDetail.getQuantity());
            realCartDetails.add(cartDetail);
        }
        model.addAttribute("realCartDetails", realCartDetails);
        model.addAttribute("user", user);
        model.addAttribute("orderNote", order.getOrderNote());
        return "client/business/billing";
    }

    @GetMapping("/admin/orders")
    public String getAllOrders(Model model, @RequestParam(value = "page", required = false, defaultValue = "1") int page) {
        Pageable pageable = PageRequest.of(page - 1, 2, Sort.by(Order_.UPDATED_AT).descending());
        Page<Order> pageOrder = this.orderService.handleGetAllOrder(pageable);
        List<Order> orders = pageOrder.getContent();
        int totalPage = pageOrder.getTotalPages();
        int currentPage = pageOrder.getNumber();
        model.addAttribute("currentPage", currentPage + 1);
        model.addAttribute("totalPage", totalPage);
        model.addAttribute("orderList", orders);
        return "admin/order/table-order";
    }

    @PostMapping("/admin/orders/status")
    public String changeOrderStatus(@RequestParam("status") String status,
            @RequestParam("orderId") String orderId) {
        if (orderId != null) {
            long id = Long.parseLong(orderId);
            Order order = this.orderService.handleFindOrderById(id);
            switch (status) {
                case "PENDING" ->
                    order.setStatus(Constant.PENDING);
                case "PROCESSING" ->
                    order.setStatus(Constant.PROCESSING);
                case "SHIPPING" ->
                    order.setStatus(Constant.SHIPPING);
                case "COMPLETED" ->
                    order.setStatus(Constant.COMPLETED);
                case "CANCELED" ->
                    order.setStatus(Constant.CANCELED);
                default ->
                    order.setStatus(Constant.PENDING);
            }
            order.setUpdatedAt(new Date());
        }
        return "redirect:/admin/orders";
    }

    @PostMapping("/admin/orders/delete")
    public String deleteOrder(@RequestParam("orderId") String orderId) {
        if(orderId != null) {
            long id = Long.parseLong(orderId);
            Order order = this.orderService.handleFindOrderById(id);
            order.setStatus(Constant.DELETED);
        }
        return "redirect:/admin/orders";
    }

}
